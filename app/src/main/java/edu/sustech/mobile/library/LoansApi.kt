package edu.sustech.mobile.library

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.sso.CasLogin
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * My-library loans: what you have borrowed, when it is due, and renewing.
 *
 * The gateway is Primo's my-account REST family
 * (`/primaws/rest/priv/myaccount/…`), reverse-engineered 2026-10-05 — the
 * same wire the Python client (`sustech_survival.lib.loans`) and the reference
 * in the sustech-dev skill (`lib-loans-myaccount-2026-10-05.md`) use. Reach it
 * through a five-step chain, every step required:
 *
 *  1. CAS ticket for `…/infra/casRedirect?ctx=/primaws`
 *  2. POST the ticket to `/primaws/pdsHandleLogin` (PDS = the Alma patron
 *     session bridge)
 *  3. GET `suprimaExtLogin?...auth=CAS` and walk its redirect hops until the
 *     landing URL carries `loginId=<ID>` — skipping this step is the classic
 *     failure: every call then answers HTTP 200 with
 *     `reply-code 0002 "The patron ID is invalid"`
 *  4. exchange the loginId: `GET /primaws/rest/pub/loginJwtCache/<ID>` → the
 *     signed-in JWT (a guest JWT cannot see loans)
 *  5. every my-account call sends `Authorization: Bearer "<jwt>"` — the SPA
 *     wraps the JWT in *literal double quotes*; keep them or the server
 *     answers 0002 again.
 *
 * Renew is a POST and burns the book's limited renewal quota, so [renew]
 * previews unless [commit] is set — mirroring the Python client's `dry_run`.
 */
class LoansApi(private val http: okhttp3.OkHttpClient) {

    /** One borrowed book, wire dates kept as-is (YYYYMMDD strings). */
    data class Loan(
        val loanId: String,
        val title: String,
        val author: String,
        val year: String,
        val barcode: String,
        val loanDate: String,
        val dueDate: String,
        val returnDate: String,
        val renewed: String,
        val callNumber: String,
        val location: String,
        val subLocation: String,
        val category: String,
    )

    /** The counters line the web account page opens with. */
    data class Counters(
        val fines: String,
        val loans: Int,
        val historyLoans: Int,
        val requests: Int,
    )

    @Volatile private var jwt: String? = null

    // -- Reads ----------------------------------------------------------------

    /** Borrowed books. [type] = `active` (out, with due dates) or `history`. */
    fun loans(type: String = "active", bulk: Int = 50): List<Loan> {
        val data = call(
            "$MYACCOUNT/loans",
            params = mapOf("bulk" to bulk.toString(), "lang" to "zh-CN",
                "offset" to "1", "type" to type),
        )
        val rows = data.optJSONObject("loans")?.optJSONArray("loan") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { i ->
            loan(rows.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    /** Fines / loans / history / requests counts. */
    fun counters(): Counters {
        val data = call("$MYACCOUNT/counters", params = mapOf("lang" to "zh-CN"))
        val actions = data.optJSONObject("listofactions")?.optJSONArray("action") ?: JSONArray()
        var fines = "0"
        var loans = 0
        var history = 0
        var requests = 0
        for (i in 0 until actions.length()) {
            val a = actions.optJSONObject(i) ?: continue
            when (a.optString("type")) {
                "Fines" -> fines = a.optString("value")
                "Loans" -> loans = a.optString("value").toIntOrNull() ?: 0
                "HistoryLoans" -> history = a.optString("value").toIntOrNull() ?: 0
                "Requests" -> requests = a.optString("value").toIntOrNull() ?: 0
            }
        }
        return Counters(fines, loans, history, requests)
    }

    // -- Writes -----------------------------------------------------------------

    /**
     * Renew one loan.
     *
     * Default is a preview: it returns the request the app *would* send and
     * touches nothing — the quota a wasted renewal burns cannot be refunded.
     * Pass [commit] = true to send it.
     */
    fun renew(loanId: String, commit: Boolean = false): JSONObject {
        val body = JSONObject().put("id", loanId).put("lang", "zh-CN")
        if (!commit) {
            return JSONObject()
                .put("dryRun", true)
                .put("url", "$MYACCOUNT/renew_loan")
                .put("body", body)
        }
        val data = call("$MYACCOUNT/renew_loan", method = "POST", json = body)
        return data.optJSONObject("loans")?.optJSONArray("loan")?.optJSONObject(0)
            ?: JSONObject()
    }

    // -- Auth chain ---------------------------------------------------------------

    /** True once the chain has run; a fresh app process starts unauthenticated. */
    val signedIn: Boolean get() = jwt != null

    fun ensureSignedIn() {
        if (jwt == null) signIn()
    }

    /**
     * Runs the five steps. Kept public so the sign-in screen can prime the
     * session while the credentials dialog is still up.
     */
    fun signIn() {
        val sid = edu.sustech.mobile.core.Credentials.sid
        val pwd = edu.sustech.mobile.core.Credentials.password
        if (sid.isEmpty() || pwd.isEmpty()) {
            throw ApiException("No school account saved", signInRequired = true)
        }

        // 1+2 — CAS ticket, then hand it to PDS.
        CasLogin.login(CAS_SERVICE, sid, pwd, xhr = false)

        // 3 — the suprimaExtLogin hop chain. Each hop is one GET; whenever a
        // 200 page carries a ticket form, POST it, exactly what the browser
        // does with its auto-submit form.
        val target = "$PRIMO/discovery/account?vid=$VID_ENCODED" +
            "&section=loans&lang=zh-CN&fromLogin=true"
        val extLogin = "$PRIMO/primaws/suprimaExtLogin" +
            "?institution=$INSTITUTION&lang=zh-CN" +
            "&target-url=" + URLEncoder.encode(target, "UTF-8") +
            "&authenticationProfile=CAS2&idpCode=CAS2&auth=CAS" +
            "&view=$VID_ENCODED&isSilent=false"
        val landing = walkHops(extLogin)
        val loginId = Regex("loginId=([^&]+)").find(landing)?.groupValues?.get(1)
            ?: throw ApiException(
                "The library session did not establish (no loginId) — try again",
            )

        // 4 — exchange loginId for the signed-in JWT.
        val mintUrl = "$PRIMO/primaws/rest/pub/loginJwtCache/$loginId?vid=$VID_ENCODED"
        val minted = get(mintUrl, jwt = null).trim().trim('"')
        if (!minted.startsWith("eyJ")) {
            throw ApiException("The library did not return a session token")
        }
        jwt = minted
    }

    /** Walks up to eight redirect/ticket hops and returns the final body URL. */
    private fun walkHops(url: String): String {
        var current = url
        repeat(8) {
            val request = Request.Builder().url(current)
                .header("User-Agent", CasLogin.UA).build()
            val response = try {
                App.httpNoRedirect.newCall(request).execute()
            } catch (e: IOException) {
                throw ApiException(e.message ?: "network error")
            }
            response.use {
                val location = it.header("Location")
                when {
                    it.isRedirect && !location.isNullOrEmpty() -> {
                        current = if (location.startsWith("/")) PRIMO + location else location
                    }
                    it.body?.string().orEmpty().contains("name=\"ticket\"") -> {
                        val ticket = Regex("name=\"ticket\" value=\"([^\"]+)\"")
                            .find(it.body?.string().orEmpty())?.groupValues?.get(1)
                        val form = FormBody.Builder()
                            .add("ticket", ticket ?: "")
                            .add("update", "Apply")
                            .build()
                        val post = Request.Builder()
                            .url("$PRIMO/primaws/pdsHandleLogin")
                            .post(form)
                            .header("User-Agent", CasLogin.UA)
                            .build()
                        val posted = try {
                            App.httpNoRedirect.newCall(post).execute()
                        } catch (e: IOException) {
                            throw ApiException(e.message ?: "network error")
                        }
                        posted.use { p ->
                            val next = p.header("Location")
                            current = when {
                                p.isRedirect && !next.isNullOrEmpty() ->
                                    if (next.startsWith("/")) PRIMO + next else next
                                else -> p.request.url.toString()
                            }
                        }
                    }
                    else -> return current
                }
            }
        }
        return current
    }

    // -- Transport ------------------------------------------------------------------

    private fun loan(row: JSONObject): Loan? {
        val id = row.optString("loanid")
        if (id.isEmpty()) return null
        return Loan(
            loanId = id,
            title = row.optString("title"),
            author = row.optString("author"),
            year = row.optString("year"),
            barcode = row.optString("itembarcode"),
            loanDate = row.optString("loandate"),
            dueDate = row.optString("duedate"),
            returnDate = row.optString("returndate"),
            renewed = row.optString("renew"),
            callNumber = row.optString("callnumber"),
            location = row.optString("mainlocationname"),
            subLocation = row.optString("secondarylocationname"),
            category = row.optString("itemcategoryname"),
        )
    }

    private fun call(
        path: String,
        params: Map<String, String>? = null,
        json: JSONObject? = null,
        method: String = "GET",
    ): JSONObject {
        ensureSignedIn()
        val token = jwt ?: throw ApiException("No library session", signInRequired = true)
        val url = buildUrl("$PRIMO$path", params)
        val body = json?.toString()?.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .method(method, body)
            .header("User-Agent", CasLogin.UA)
            .header("X-Requested-With", "XMLHttpRequest")
            // The quotes are literal — the SPA sends them, the server wants them.
            .header("Authorization", "Bearer \"$token\"")
            .build()
        val text = try {
            App.httpNoRedirect.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code >= 400) throw ApiException("library answered HTTP ${response.code}")
                payload
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        val envelope = parse(text) ?: throw ApiException("the library returned a non-JSON reply")
        if (envelope.optString("reply-code") == "0002") {
            // Patron session expired — one silent re-auth, then retry.
            jwt = null
            signIn()
            return call(path, params, json, method)
        }
        if (envelope.optString("status") != "ok") {
            throw ApiException(
                "library: ${envelope.optString("reply-text")}",
            )
        }
        return envelope.optJSONObject("data") ?: JSONObject()
    }

    private fun get(url: String, jwt: String?): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", CasLogin.UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (jwt != null) header("Authorization", "Bearer \"$jwt\"") }
            .build()
        return try {
            App.httpNoRedirect.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code >= 400) throw ApiException("library answered HTTP ${response.code}")
                payload
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>?): String {
        if (params.isNullOrEmpty()) return base
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v, "UTF-8")
        }
        return "$base?$query"
    }

    private fun parse(text: String): JSONObject? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return null
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val PRIMO = "https://sustc.primo.exlibrisgroup.com.cn"
        const val INSTITUTION = "86SUSTC_INST"
        const val VID_ENCODED = "86SUSTC_INST%3A86SUSTC"

        /** CAS entry for the Primo side; lands on `/infra/casRedirect`. */
        val CAS_SERVICE = "$PRIMO/infra/casRedirect?ctx=/primaws"
        const val MYACCOUNT = "/primaws/rest/priv/myaccount"
    }
}
