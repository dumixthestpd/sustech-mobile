package edu.sustech.mobile.pms

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.sso.Session
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Server phrases that mean "your session is gone" rather than "your request was
 * wrong". They arrive inside a normal error envelope, so they have to be
 * recognised and turned into a silent re-login — never shown as a network error.
 */
private val STALE_MARKERS = listOf("请用户重新登录", "重新登录", "无效会话", "未登录", "请登录", "用户未登录")

/**
 * PMS failures are [edu.sustech.mobile.core.ApiException]s; the alias keeps the
 * call sites readable inside this package.
 */
typealias PmsException = edu.sustech.mobile.core.ApiException

/**
 * The whole PMS HTTP surface, one method per website page.
 *
 * Everything the 联创 print site does lives behind `/api/client/…`; the
 * envelope is always `{code, message, result}` and `code == 0` means success.
 * This class owns the wire format so the UI never touches a JSONObject.
 *
 * The base URL is read on every call, so switching servers at runtime (login
 * screen or account tab) takes effect without rebuilding the client.
 */
class PmsApi(
    private val http: OkHttpClient,
    private val baseUrl: () -> String = { AppConfig.DEFAULT_BASE_URL },
) {

    enum class Reachability { AVAILABLE, REPLIED, UNREACHABLE }

    /** A valid response from the public PMS API proves that printing is reachable. */
    fun reachability(): Reachability {
        val url = (baseUrl() + "/api/client/Auth/PublicKey").toHttpUrlOrNull()
            ?: return Reachability.UNREACHABLE
        val request = Request.Builder()
            .url(url)
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", baseUrl() + "/client/new/cprintPc/")
            .build()
        return try {
            val call = http.newCall(request).apply { timeout().timeout(8, TimeUnit.SECONDS) }
            call.execute().use { response ->
                if (response.code != 200) return@use Reachability.REPLIED
                val body = parseOrNull(response.body?.string().orEmpty())
                if (body?.optInt("code", -1) == 0 &&
                    !body?.optJSONObject("result")?.optString("publicKey").isNullOrEmpty()
                ) Reachability.AVAILABLE else Reachability.REPLIED
            }
        } catch (_: IOException) {
            Reachability.UNREACHABLE
        }
    }

    // -- Endpoints the site exposes -------------------------------------------

    /** POST /client/Auth/Check — who is signed in. Also the session probe. */
    fun check(): AccountInfo = withRelogin { checkSession() }

    /**
     * The same call with no re-login wrapper.
     *
     * The sign-in logic itself probes the session with this: going through
     * [check] there would re-enter the re-login path and recurse.
     */
    fun checkSession(): AccountInfo {
        val body = postJson("/api/client/Auth/Check", JSONObject())
        val result = body.optJSONObject("result") ?: JSONObject()
        return AccountInfo(
            trueName = result.optString("szTrueName", ""),
            logonName = result.optString("szLogonName", ""),
            raw = result,
        )
    }

    /** GET /client/Station/GetSrvList — the print-point dropdown. */
    fun serverGroups(): List<ServerGroup> =
        Cache.get("pms.groups", Cache.TTL_SEMESTER) { serverGroupsUncached() }

    private fun serverGroupsUncached(): List<ServerGroup> = withRelogin {
        val array = getArray("/api/client/Station/GetSrvList", mapOf("timestamp" to "0"))
        return@withRelogin (0 until array.length()).map { ServerGroup.from(array.getJSONObject(it)) }
    }

    /** GET /client/Station/GetList — every printer / copier / scanner. */
    fun stations(): List<Station> =
        Cache.get("pms.stations", Cache.TTL_STATIONS) { stationsUncached() }

    private fun stationsUncached(): List<Station> = withRelogin {
        val array = getArray("/api/client/Station/GetList", mapOf("timestamp" to "0"))
        return@withRelogin (0 until array.length()).map { Station.from(array.getJSONObject(it)) }
    }

    /** GET /client/PrintJob/Get — uploaded but not yet printed. */
    fun printJobs(force: Boolean = false): List<PrintJob> =
        Cache.get("pms.jobs", Cache.TTL_LIVE, force) { printJobsUncached() }

    private fun printJobsUncached(): List<PrintJob> = withRelogin {
        val array = getArray("/api/client/PrintJob/Get", mapOf("timestamp" to "0"))
        return@withRelogin (0 until array.length()).map { PrintJob.from(array.getJSONObject(it)) }
    }

    /**
     * POST /client/PrintJob/Del — delete a queued document.
     *
     * Returns null on success, or the server message on failure (the site
     * reports "already printed" as `code = -1`, not as an HTTP error).
     */
    fun deletePrintJob(jobId: Long): String? = withRelogin {
        val body = postJson(
            "/api/client/PrintJob/Del",
            JSONObject().put("dwJobId", jobId).put("dwOldJobId", jobId),
            throwOnError = false,
        )
        val code = body.optInt("code", -1)
        if (code == 0) Cache.invalidate("pms.jobs")
        return@withRelogin if (code == 0) null else body.optString("message", "code=$code")
    }

    /** GET /client/Scan/Get — scanned documents waiting for pickup. */
    fun scanJobs(force: Boolean = false): List<ScanJob> =
        Cache.get("pms.scans", Cache.TTL_STATIONS, force) { scanJobsUncached() }

    private fun scanJobsUncached(): List<ScanJob> = withRelogin {
        val array = getArray("/api/client/Scan/Get", mapOf("timestamp" to "0"))
        return@withRelogin (0 until array.length()).map { ScanJob.from(array.getJSONObject(it)) }
    }

    /** POST /client/Scan/Del — delete a scanned document. */
    fun deleteScanJob(jobId: Long): String? = withRelogin {
        val body = postJson(
            "/api/client/Scan/Del",
            JSONObject().put("dwJobId", jobId),
            throwOnError = false,
        )
        val code = body.optInt("code", -1)
        if (code == 0) Cache.invalidate("pms.scans")
        return@withRelogin if (code == 0) null else body.optString("message", "code=$code")
    }

    /**
     * POST /client/Report/DetailPage — paginated usage records.
     * Returns the rows plus the total page count the server reports.
     */
    fun usage(
        begin: String,
        end: String,
        type: Int,
        page: Int,
        pageSize: Int,
    ): Pair<List<UsageRecord>, Int> =
        Cache.get("pms.usage.$begin.$end.$type.$page.$pageSize", Cache.TTL_STATIONS) {
            usageUncached(begin, end, type, page, pageSize)
        }

    private fun usageUncached(
        begin: String,
        end: String,
        type: Int,
        page: Int,
        pageSize: Int,
    ): Pair<List<UsageRecord>, Int> = withRelogin {
        val payload = JSONObject()
            .put("dwBeginDate", begin)
            .put("dwEndDate", end)
            .put("dwType", type)
            .put("dwPageNo", page)
            .put("dwRowCount", pageSize)
        val body = postJson("/api/client/Report/DetailPage", payload)
        val array = body.optJSONArray("result") ?: JSONArray()
        val rows = (0 until array.length()).map { UsageRecord.from(array.getJSONObject(it)) }
        val totalPages = body.optInt("dwTotalPage", 1).coerceAtLeast(1)
        return@withRelogin rows to totalPages
    }

    /**
     * POST /client/CloudPrint/Upload — the 云打印 page.
     *
     * Uploading is free; money is taken at the printer when the job is
     * collected. `dwFrom = 0` means "all pages" and `dwTo` is ignored then.
     */
    fun upload(
        file: File,
        options: UploadOptions,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): String {
        val url = (baseUrl() + "/api/client/CloudPrint/Upload").toHttpUrlOrNull()
            ?: throw PmsException("Bad server URL: ${baseUrl()}")
        val before = runCatching { printJobs().map { it.jobId } }.getOrDefault(emptyList())
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("szPath", file.name, ProgressRequestBody(file, onProgress))
            .addFormDataPart("dwColor", options.color.toString())
            .addFormDataPart("dwPaperId", options.paper.toString())
            .addFormDataPart("dwDuplex", options.duplex.toString())
            .addFormDataPart("dwFrom", options.pageFrom.toString())
            .addFormDataPart("dwTo", options.pageTo.toString())
            .addFormDataPart("dwCopies", options.copies.toString())
            .addFormDataPart("BackURL", "result.html")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Referer", baseUrl() + "/client/new/cprintPc/")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        val answer = try {
            execute(request, allowErrorStatus = true)
        } catch (e: ApiException) {
            // An accepted upload can be followed by an answer we cannot use: the
            // result page, or a redirect aimed at a plain-HTTP address. The queue
            // is the verdict — if the job is there, it worked.
            if (confirmQueued(file.name, before)) {
                return "uploaded — confirmed in the print queue (the answer was: ${e.message})"
            }
            throw e
        }
        if (answer.text.startsWith("Access forbidden")) {
            throw PmsException("Off campus", offCampus = true)
        }
        val json = parseOrNull(answer.text)
        if (json == null) {
            // The page posts with BackURL=result.html, so an accepted upload can
            // answer with the HTML result page instead of a JSON envelope — which
            // the browser never notices. Believe the queue, not the body: a job
            // that is not in the queue is the only real failure.
            Cache.invalidate("pms.jobs")
            if (confirmQueued(file.name, before)) {
                return "uploaded — confirmed in the print queue"
            }
            throw PmsException(
                "HTTP ${answer.code} and the job never reached the queue: ${snippet(answer.text)}",
            )
        }
        val code = json.optInt("code", -1)
        if (code != 0) {
            val message = json.optString("message", "code=$code")
            if (STALE_MARKERS.any { message.contains(it) }) {
                throw PmsException("Print session expired", signInRequired = true)
            }
            throw PmsException("$message (HTTP ${answer.code})")
        }
        return json.optString("message", "").ifEmpty { "ok" }
    }

    /**
     * True once [fileName] appears as a job that was not in [before].
     *
     * Uploading is the one write whose answer cannot be trusted to mean success
     * (see above), so the queue is the source of truth. Blocks the calling
     * thread briefly — call it from the IO dispatcher.
     */
    private fun confirmQueued(fileName: String, before: List<Long>): Boolean {
        repeat(3) { attempt ->
            val queued = runCatching { printJobs(force = true) }.getOrNull().orEmpty()
            if (queued.any { it.fileName == fileName && it.jobId !in before }) return true
            if (attempt < 2) Thread.sleep(1500)
        }
        return false
    }

    /** Tag-stripped, whitespace-collapsed preview of a response body. */
    private fun snippet(text: String): String {
        val plain = text.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        return if (plain.isEmpty()) "(empty body)" else plain.take(160)
    }

    /**
     * Runs [block], and when the failure is "the session is gone" signs in
     * again with the stored school account and retries once. Every expiry is
     * therefore invisible to the screen: no user-visible sign-in prompts.
     */
    private fun <T> withRelogin(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.signInRequired && Session.reloginPrint()) block() else throw e
    }

    // -- Transport ------------------------------------------------------------

    private fun getArray(path: String, params: Map<String, String>): JSONArray {
        val builder = (baseUrl() + path).toHttpUrlOrNull()?.newBuilder()
            ?: throw PmsException("Bad server URL: ${baseUrl()}")
        for ((k, v) in params) builder.addQueryParameter(k, v)
        val request = Request.Builder()
            .url(builder.build())
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", baseUrl() + "/client/new/cprintPc/")
            .build()
        val answer = execute(request)
        val json = parseOrNull(answer.text)
            ?: throw PmsException("Non-JSON response — session gone", signInRequired = true)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val message = json.optString("message", "code=$code")
            // 无效会话 / 未登录 arrive as a normal error envelope but mean the
            // session is gone, which the caller must handle as a re-login.
            if (STALE_MARKERS.any { message.contains(it) }) {
                throw PmsException("Print session expired", signInRequired = true)
            }
            throw PmsException(message)
        }
        return json.optJSONArray("result") ?: JSONArray()
    }

    private fun postJson(path: String, payload: JSONObject, throwOnError: Boolean = true): JSONObject {
        val request = Request.Builder()
            .url(baseUrl() + path)
            .post(payload.toString().toRequestBody(JSON))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Referer", baseUrl() + "/client/new/cprintPc/")
            .build()
        val answer = execute(request)
        val json = parseOrNull(answer.text)
            ?: throw PmsException("Non-JSON response — session gone", signInRequired = true)
        if (throwOnError && json.optInt("code", -1) != 0) {
            val code = json.optInt("code", -1)
            throw PmsException(json.optString("message", "code=$code"))
        }
        return json
    }

    /** A response body plus the status that produced it, so errors can be specific. */
    private data class HttpAnswer(val code: Int, val text: String)

    private fun execute(request: Request, allowErrorStatus: Boolean = false): HttpAnswer {
        // Campus networks drop TLS handshakes routinely ("connection closed");
        // one retry turns those into a non-event instead of an error on screen.
        return try {
            executeOnce(request, allowErrorStatus)
        } catch (e: ApiException) {
            if (e.message?.contains("close", ignoreCase = true) != true) throw e
            Thread.sleep(700)
            executeOnce(request, allowErrorStatus)
        }
    }

    private fun executeOnce(request: Request, allowErrorStatus: Boolean): HttpAnswer {
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code in 300..399) {
                    // Redirects are answers, not detours. A ticket redirect means
                    // the session is gone; an http:// target is the server's own
                    // mismatch and must be reported as such, never chased.
                    val location = response.header("Location").orEmpty()
                    when {
                        location.contains(Hosts.CAS) || location.contains("/cas/") ->
                            throw PmsException("Session gone (server redirected to CAS)", signInRequired = true)
                        location.startsWith("http://") ->
                            throw PmsException("server redirected to a plain-HTTP address: $location")
                        else -> throw PmsException(
                            "unexpected redirect (HTTP ${response.code}) to ${location.ifEmpty { "nowhere" }}",
                        )
                    }
                }
                if (response.code == 403 || text.startsWith("Access forbidden")) {
                    throw PmsException("Off campus (HTTP ${response.code})", offCampus = true)
                }
                if (response.code == 401) {
                    throw PmsException("PMS session expired", signInRequired = true)
                }
                if (response.code == 413) {
                    throw PmsException("File too large (HTTP 413)")
                }
                if (!allowErrorStatus && response.code >= 400) {
                    throw PmsException("HTTP ${response.code}", httpStatus = response.code)
                }
                return HttpAnswer(response.code, text)
            }
        } catch (e: IOException) {
            throw PmsException(e.message ?: "network error")
        }
    }

    private fun parseOrNull(text: String): JSONObject? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** Options for a cloud-print upload — the five controls on the 云打印 page. */
data class UploadOptions(
    val color: Int = ColorMode.BW,
    val paper: Int = Paper.UNSPECIFIED,
    val duplex: Int = Duplex.SINGLE,
    val pageFrom: Int = 0,
    val pageTo: Int = 0,
    val copies: Int = 1,
) {
    /** The site sends dwFrom=0 for "all pages" and ignores dwTo. */
    fun normalised(): UploadOptions {
        val from = if (pageFrom <= 0) 0 else pageFrom.coerceAtLeast(1)
        val to = if (from == 0) 0 else maxOf(from, if (pageTo <= 0) from else pageTo)
        return copy(pageFrom = from, pageTo = to, copies = copies.coerceAtLeast(1))
    }
}

/** File body that reports upload progress to the UI. */
private class ProgressRequestBody(
    private val file: File,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {

    override fun contentType() = "application/octet-stream".toMediaType()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length()
        var sent = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sent += read
                onProgress(sent, total)
            }
        }
    }
}
