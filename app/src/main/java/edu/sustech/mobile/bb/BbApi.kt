package edu.sustech.mobile.bb

import android.util.Log
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.core.Hosts
import edu.sustech.mobile.sso.CasLogin
import edu.sustech.mobile.sso.Session
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Blackboard Learn — the same REST surface the Python `sustech_survival.bb`
 * module uses, ported to OkHttp.
 *
 *   1. GET /learn/api/public/v1/users/me            → user id
 *   2. GET /learn/api/public/v1/users/{uid}/courses → enrollments, with the
 *      course object inlined (`expand=course`) so names and terms arrive in
 *      one round trip — never one request per course
 *   3. GET /learn/api/public/v1/courses/{id}/gradebook/columns → assignment
 *      columns; the ones with a `due` date feed the Deadlines tab
 *
 * Auth is the school CAS via [CasLogin] with Blackboard's service URL; the
 * cookies land in the shared host-scoped CookieStore, so the REST calls ride
 * the same session a browser would have. BB answers a dead session with a
 * plain 401 — that maps to `signInRequired`, which [withRelogin] turns into a
 * silent re-login instead of an error screen.
 */
class BbApi(private val http: OkHttpClient) {

    /** Same cookie jar, redirects followed — only for one attachment hop. */
    private val httpFollow: OkHttpClient by lazy { http.newBuilder().followRedirects(true).build() }

    /** Ceiling for one attachment: a lab manual is ~2–30 MB. */
    private val maxAttachmentBytes = 128L * 1024 * 1024

    /** One enrolled course, as the app displays it. */
    data class Course(
        val id: String,
        val name: String,
        val term: String,
        val lastAccessed: String,
    )

    /**
     * One assignment column with a due date.
     *
     * [due] is display-ready (local time); [at] is kept for ordering —
     * `null` when the server sent something unparseable.
     */
    data class Deadline(
        val courseName: String,
        val title: String,
        val due: String,
        val at: Instant?,
    )

    /** All enrolled courses, cached like the TIS lists. */
    fun courses(allowRelogin: Boolean = true): List<Course> = Cache.get("bb.courses", Cache.TTL_LIST) {
        withRelogin(allowRelogin) { coursesUncached() }
    }

    private fun coursesUncached(): List<Course> {
        val uid = getJson("/learn/api/public/v1/users/me").optString("id")
        if (uid.isEmpty()) throw ApiException("Blackboard did not report a user id")
        // Term ids are opaque (`_58_1`); the site's own term table is what
        // turns them into "2026秋" — one extra call, same as the Python client.
        val terms: Map<String, String> = runCatching {
            val results = getJson("/learn/api/public/v1/terms?limit=200")
                .optJSONArray("results") ?: return@runCatching emptyMap()
            val map = HashMap<String, String>()
            for (i in 0 until results.length()) {
                val term = results.optJSONObject(i) ?: continue
                val id = term.optString("id")
                if (id.isNotEmpty()) map[id] = term.optString("name")
            }
            map
        }.getOrDefault(emptyMap())
        val enrollments = getJson(
            "/learn/api/public/v1/users/$uid/courses?expand=course&limit=200",
        ).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Course>(enrollments.length())
        for (i in 0 until enrollments.length()) {
            val enrollment = enrollments.optJSONObject(i) ?: continue
            val courseId = enrollment.optString("courseId")
            if (courseId.isEmpty()) continue
            val course = enrollment.optJSONObject("course") ?: JSONObject()
            val termId = course.optString("termId")
            out += Course(
                id = courseId,
                name = course.optString("name").ifEmpty { "(unnamed course)" },
                term = terms[termId] ?: termId,
                lastAccessed = enrollment.optString("lastAccessed").take(10),
            )
        }
        // De-duplicate by course id, latest access first — mirrors the
        // Python `scrape_enrolled_courses` seen-ids pass.
        return out.distinctBy { it.id }.sortedByDescending { it.lastAccessed }
    }

    /**
     * Assignment columns that carry a due date, across all enrolled courses.
     *
     * `due` lives **inside** `grading`, not at the column's top level
     * (verified live 2026-09-14: `{"grading":{"type":"Attempts","due":
     * "2026-10-15T23:59:00.000Z"}}`) — reading it off the column silently
     * matched nothing.
     *
     * One request per course (the same shape as the Python deadline flow);
     * a course whose gradebook cannot be read is skipped, not fatal — one
     * locked course must not blank the whole list.
     */
    fun deadlines(allowRelogin: Boolean = true): List<Deadline> = Cache.get("bb.deadlines", Cache.TTL_LIST) {
        withRelogin(allowRelogin) {
            val all = courses(allowRelogin).flatMap { course ->
                try {
                    val columns = getJson(
                        "/learn/api/public/v1/courses/${course.id}/gradebook/columns" +
                            "?_fields=id,name,contentId,grading",
                    ).optJSONArray("results") ?: return@flatMap emptyList()
                    (0 until columns.length()).mapNotNull { i ->
                        val column = columns.optJSONObject(i) ?: return@mapNotNull null
                        val title = column.optString("name")
                        val raw = column.optJSONObject("grading")?.optString("due").orEmpty()
                        if (title.isEmpty() || raw.isEmpty()) null
                        else Deadline(course.name, title, formatDue(raw), parseDue(raw))
                    }
                } catch (_: ApiException) {
                    emptyList()
                }
            }
            // What is still due comes first (soonest on top); what already
            // passed trails behind, most recent first — a stale spring
            // assignment must not bury the deadline that is tomorrow.
            val now = Instant.now()
            val (open, closed) = all.partition { it.at == null || it.at >= now }
            open.sortedBy { it.at ?: Instant.MAX } +
                closed.sortedByDescending { it.at ?: Instant.MIN }
        }
    }

    private fun parseDue(raw: String): Instant? = runCatching {
        OffsetDateTime.parse(raw).toInstant()
    }.getOrNull()

    /** BB sends ISO-8601 UTC (`2026-10-15T23:59:00.000Z`) → local `10-16 07:59`. */
    private fun formatDue(raw: String): String = parseDue(raw)
        ?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        ?: raw.take(16).replace("T", " ")

    /** True when a live session answers — the Account tab's session probe. */
    fun isSignedIn(): Boolean = runCatching {
        getJson("/learn/api/public/v1/users/me?_fields=id").has("id")
    }.getOrElse { error ->
        Log.w("BbApi", "session probe failed: ${error.message}")
        false
    }

    /**
     * Retries once after a silent CAS re-login when BB answers 401 — the same
     * contract `TisApi.withRelogin` gives the TIS screens.
     *
     * `allowRelogin = false` keeps the *widget* from driving a CAS sign-in: a
     * background card that re-authenticates on a timer gets throttled by CAS
     * and then reports failures the user cannot act on. Widgets show cached
     * data and ask the app to sign in instead.
     */
    private fun <T> withRelogin(allowRelogin: Boolean = true, block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (allowRelogin && e.signInRequired && Session.reloginBb()) block() else throw e
    }

    /**
     * One content item with its files — what the item screen renders.
     *
     * Attachments are a separate call and may legitimately be absent (a folder,
     * an inline-only page): that is an answer, not an error.
     */
    data class ItemDetail(
        val title: String,
        val kind: BbItem.Kind,
        val bodyText: String,
        val attachments: List<BbAttachment>,
    )

    /** One level of a course's content tree, cached per `(course, parent)`. */
    fun contents(courseId: String, parentId: String? = null, allowRelogin: Boolean = true): List<BbItem> =
        Cache.get("bb.tree.${BbWire.coreId(courseId)}.${parentId ?: "root"}", Cache.TTL_LIST) {
            withRelogin(allowRelogin) { contentsUncached(courseId, parentId) }
        }

    private fun contentsUncached(courseId: String, parentId: String?): List<BbItem> {
        val bid = BbWire.bracket(courseId)
        val fields = "?_fields=id,title,contentHandler,hasChildren,availability&limit=200"
        var path = "/learn/api/public/v1/courses/$bid/contents" +
            (parentId?.let { "/${BbWire.bracket(it)}/children" } ?: "") + fields
        val out = ArrayList<BbItem>()
        val seen = HashSet<String>()
        repeat(30) {
            if (!seen.add(path)) throw ApiException("Blackboard pagination looped")
            val page = getJson(path)
            out += BbWire.contents(page)
            val next = BbWire.nextPage(page) ?: return out
            path = when {
                next.startsWith("/") -> next
                next.startsWith(Hosts.BLACKBOARD) -> next.removePrefix(Hosts.BLACKBOARD)
                else -> throw ApiException("Blackboard pagination left the host")
            }
        }
        throw ApiException("Blackboard pagination exceeded 30 pages")
    }

    /** One course announcement — what Blackboard calls a notification. */
    data class Notice(val title: String, val date: String, val body: String)

    /**
     * A course's announcements, newest first.
     *
     * They are read from the course's own announcements collection rather than
     * from the content tree: an announcement is not a content item, and treating
     * it as one would put unopenable rows among the folders.
     */
    fun announcements(courseId: String, allowRelogin: Boolean = true): List<Notice> =
        Cache.get("bb.notices.${BbWire.coreId(courseId)}", Cache.TTL_LIST) {
            withRelogin(allowRelogin) {
                val results = getJson(
                    "/learn/api/public/v1/courses/${BbWire.bracket(courseId)}/announcements?limit=20",
                ).optJSONArray("results") ?: return@withRelogin emptyList()
                (0 until results.length()).mapNotNull { i ->
                    val item = results.optJSONObject(i) ?: return@mapNotNull null
                    Notice(
                        title = item.optString("title").ifEmpty { "(untitled)" },
                        date = item.optString("created").take(10),
                        body = BbWire.bodyText(item.optString("body"), limit = 4000),
                    )
                }
            }
        }

    /** One item: title, kind, flattened body, and every attachment. */
    fun item(courseId: String, contentId: String, allowRelogin: Boolean = true): ItemDetail =
        Cache.get("bb.item.${BbWire.coreId(courseId)}.${BbWire.coreId(contentId)}", Cache.TTL_LIST) {
            withRelogin(allowRelogin) {
                val bid = BbWire.bracket(courseId)
                val cid = BbWire.bracket(contentId)
                val json = getJson(
                    "/learn/api/public/v1/courses/$bid/contents/$cid?_fields=id,title,body,contentHandler,hasChildren",
                )
                val body = json.optString("body")
                val attachments = runCatching {
                    BbWire.attachments(
                        getJson("/learn/api/public/v1/courses/$bid/contents/$cid/attachments?_fields=id,fileName,mimeType"),
                    )
                }.getOrDefault(emptyList())
                ItemDetail(
                    title = json.optString("title").ifEmpty { "(untitled)" },
                    kind = BbWire.kindOf(json.optJSONObject("contentHandler")?.optString("id").orEmpty()),
                    bodyText = BbWire.bodyText(body),
                    attachments = attachments,
                )
            }
        }

    /**
     * The bytes of one attachment.
     *
     * [App.http] does not follow redirects on purpose (a 302 to CAS means the
     * session died), so the hop is handled here: CAS → sign-in required, any
     * other 302 → one follow with the shared cookie jar.
     */
    fun download(
        courseId: String,
        contentId: String,
        attachmentId: String,
        allowRelogin: Boolean = true,
    ): ByteArray = withRelogin(allowRelogin) {
        fetchBytes(
            "/learn/api/public/v1/courses/${BbWire.bracket(courseId)}/contents/" +
                "${BbWire.bracket(contentId)}/attachments/$attachmentId/download",
        )
    }

    /** Inline files a document body points at (`bbcswebdav/…`, root-relative). */
    fun downloadInline(path: String): ByteArray = fetchBytes("/" + path.trimStart('/'))

    private fun fetchBytes(path: String): ByteArray {
        val url = if (path.startsWith("http")) path else Hosts.BLACKBOARD + path
        val request = Request.Builder().url(url).get().header("User-Agent", CasLogin.UA).build()
        val first = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        first.use {
            if (it.code in intArrayOf(301, 302, 303, 307, 308)) {
                val location = it.header("Location").orEmpty()
                if (location.contains("cas.sustech.edu.cn")) {
                    throw ApiException("Blackboard session expired", signInRequired = true)
                }
                val resolved = if (location.startsWith("http")) location else Hosts.BLACKBOARD + location
                val second = try {
                    httpFollow.newCall(
                        Request.Builder().url(resolved).get().header("User-Agent", CasLogin.UA).build(),
                    ).execute()
                } catch (e: IOException) {
                    throw ApiException(e.message ?: "network error")
                }
                second.use { return readBytesOnce(it) }
            }
            if (it.code == 401) throw ApiException("Blackboard session expired", signInRequired = true)
            if (it.code >= 400) throw ApiException("Blackboard answered HTTP ${it.code}")
            return readBytesOnce(it)
        }
    }

    /** An OkHttp body is single-use: read it once, then measure what you read. */
    private fun readBytesOnce(response: okhttp3.Response): ByteArray {
        val body = response.body ?: throw ApiException("Blackboard sent an empty file")
        val declared = body.contentLength()
        if (declared > maxAttachmentBytes) throw ApiException("That file is too large to open in the app")
        val bytes = body.bytes()
        if (bytes.isEmpty()) throw ApiException("Blackboard sent an empty file")
        if (bytes.size > maxAttachmentBytes) throw ApiException("That file is too large to open in the app")
        return bytes
    }

    // -- Transport ------------------------------------------------------------

    private fun getJson(path: String): JSONObject {
        val request = Request.Builder()
            .url(Hosts.BLACKBOARD + path)
            .get()
            .header("User-Agent", CasLogin.UA)
            .header("Accept", "application/json")
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                when {
                    // BB's only stale signal: a plain 401 on every REST call.
                    response.code == 401 -> throw ApiException(
                        "Blackboard session expired",
                        signInRequired = true,
                    )
                    response.code >= 400 -> throw ApiException(
                        "Blackboard answered HTTP ${response.code}",
                    )
                    else -> response.body?.string().orEmpty()
                }
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        val trimmed = text.trimStart()
        // Blackboard's REST API only ever answers JSON. An HTML body at HTTP
        // 200 means SSO bounced us to the sign-in page — that is an expired
        // session, not a mystery failure, and the screens must say so.
        if (!trimmed.startsWith("{")) {
            throw ApiException("Blackboard session expired", signInRequired = true)
        }
        return try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            throw ApiException("Blackboard returned malformed JSON")
        }
    }
}
