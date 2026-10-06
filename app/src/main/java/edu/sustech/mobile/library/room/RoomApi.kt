package edu.sustech.mobile.library.room

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.sso.CasLogin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A room category with its right-now occupancy (讨论间, 会议室, …). */
data class RoomCategory(val name: String, val idle: Int, val total: Int)

/** One bookable room, with where it is. */
data class RoomInfo(
    val devId: Int,
    val name: String,
    val lab: String,
    /** The service's minimum session length in minutes (10 for the rooms measured). */
    val minMinutes: Int,
    val free: Boolean,
)

/** A room reservation this account holds. */
data class RoomReservation(
    val resvId: Int,
    val uuid: String,
    val title: String,
    val room: String,
    val lab: String,
    val begin: String,
    val end: String,
    val status: Int,
) {
    /** The service's status bitmask, as the words its own page uses. */
    val state: String get() = when {
        status and 16 != 0 -> "violated"
        status and 8 != 0 -> "finished"
        status and 4 != 0 -> "started"
        else -> "upcoming"
    }
}

/**
 * The IC library's discussion/meeting rooms (`booking.lib.sustech.edu.cn/ic-web`).
 *
 * 🔴 Its sign-in is not the ordinary one. The CAS service URL is minted per login
 * by the service's own authcenter, so it has to be resolved first:
 *
 *   1. `GET /ic-web/auth/address` → the authcenter URL
 *   2. `GET <authcenter>/toLoginPage` → 302 to CAS carrying a one-off service URL
 *   3. sign in at **that** CAS URL, and follow the ticket
 *   4. the relay chain's last hop sets `ic-cookie`, which every read then rides
 *
 * Also campus-only: off campus the server answers 403 with a fixed plain-text body
 * before any auth runs, so that is reported as a location problem rather than a
 * login one.
 *
 * Booking rules the service enforces (from the library's 讨论间使用办法, and shown
 * in the UI rather than guessed at): up to 2 days ahead, at most 2 hours per
 * booking, rooms for 3+ people need co-applicants, and cancelling later than
 * 10 minutes before the start can still count as a no-show. Co-applicants are
 * deliberately not offered here — that stays on the official page.
 */
class RoomApi(private val http: OkHttpClient) {

    @Volatile
    private var signedIn = false

    @Volatile
    private var myAccNo = 0

    // -- Session -------------------------------------------------------------

    /**
     * Makes sure `ic-cookie` is in the jar. Cheap when it already is: the cookie
     * being present is the check, so no extra request goes out.
     */
    @Synchronized
    fun ensureSession(force: Boolean = false) {
        if (!force && signedIn && cookieHeader().isNotEmpty()) return
        if (!Credentials.configured) {
            throw ApiException("room: no stored account", signInRequired = true)
        }
        // Drop only this host's cookies: a rejected one is what makes the relay
        // chain loop, and the CAS cookie is the whole app's session — never that.
        App.cookies.clearHost(HOST)
        val casUrl = resolveCasUrl(authAddress())
        CasLogin.loginAtCasUrl(casUrl, Credentials.sid, Credentials.password)
        if (cookieHeader().isEmpty()) {
            throw ApiException("room: the booking service refused the sign-in", signInRequired = true)
        }
        signedIn = true
    }

    /** Step 1: where this login has to go. */
    private fun authAddress(): String {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            .addPathSegments("ic-web/auth/address")
            .addQueryParameter("finalAddress", FINAL_PAGE)
            .addQueryParameter("errPageUrl", ERROR_PAGE)
            .addQueryParameter("manager", "false")
            .addQueryParameter("consoleType", "16")
            .build()
        val body = get(url.toString())
        if (body.optInt("code") != 0) {
            throw ApiException("room: no sign-in address (${body.optString("message")})")
        }
        val address = body.optString("data")
        if (!address.contains("authcenter/toLoginPage")) {
            throw ApiException("room: unexpected sign-in address")
        }
        return address
    }

    /** Step 2: the authcenter's one-off redirect to CAS. */
    private fun resolveCasUrl(authUrl: String): String {
        val request = Request.Builder()
            .url(authUrl)
            .get()
            .header("User-Agent", CasLogin.UA)
            .build()
        try {
            App.httpNoRedirect.newCall(request).execute().use { response ->
                val location = response.header("Location").orEmpty()
                if (!location.contains(CAS_HOST)) {
                    throw ApiException("room: the sign-in page did not hand over to CAS")
                }
                return location
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }

    private fun cookieHeader(): String = App.cookies.headerFor(HOST)

    // -- Reads ---------------------------------------------------------------

    /**
     * Right-now occupancy per category, as the service's own home page shows it.
     *
     * Note this list carries **no ids** — only a name — so it can summarise but not
     * be drilled into.
     */
    fun categories(): List<RoomCategory> {
        val data = call("/home/page/room/idle").optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map {
            RoomCategory(
                name = it.optString("name"),
                // The service spells it "idelQuantity".
                idle = it.optInt("idelQuantity"),
                total = it.optInt("totalQuantity"),
            )
        }
    }

    /**
     * Every room of a classKind, each with the floor it sits on and whether it is
     * taken right now.
     *
     * 🔴 Ask **once** and group by the labs the reply itself carries. The inventory
     * endpoint ignores both `kindId` and `labId` — measured 2026-10-06: the same 26
     * rooms come back for every lab id, and a query for the 3–7-person kind returns
     * the 1–3-person rooms too. Walking the lab list multiplied the inventory by the
     * number of labs (26 → 260), so per-lab and per-kind loops are both wrong here.
     */
    fun allRooms(classKind: Int = RESEARCH_ROOMS): List<RoomInfo> {
        val groups = call(
            "/roomDevice/roomInfos",
            "classKind" to classKind.toString(),
            "kindId" to "",
            "labId" to "",
        ).optJSONArray("data") ?: JSONArray()
        val rooms = mutableListOf<RoomInfo>()
        for (g in 0 until groups.length()) {
            val labInfos = groups.optJSONObject(g)?.optJSONArray("labInfos") ?: continue
            for (l in 0 until labInfos.length()) {
                val lab = labInfos.optJSONObject(l) ?: continue
                val labName = lab.optString("labName")
                val roomInfos = lab.optJSONArray("roomInfos") ?: continue
                for (r in 0 until roomInfos.length()) {
                    val room = roomInfos.optJSONObject(r) ?: continue
                    rooms += RoomInfo(
                        devId = room.optInt("devId"),
                        name = room.optString("devName"),
                        lab = labName,
                        minMinutes = room.optInt("minResvTime"),
                        // Populated exactly when the room is taken.
                        free = (room.optJSONArray("resvInfos")?.length() ?: 0) == 0,
                    )
                }
            }
        }
        return rooms
    }

    /** How many reservations this account holds. */
    fun count(): Int = call("/reserve/count").optInt("data")

    /**
     * This account's reservations in a date range.
     *
     * @param needStatus server-side filter matching the service's own tabs:
     *        6 = not started, 4 = started, 16 = missed, 8 = finished; null for all.
     */
    fun reservations(days: Int = 60, needStatus: Int? = null, pageSize: Int = 20): List<RoomReservation> {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val from = format.format(Date())
        val to = format.format(Date(System.currentTimeMillis() + days * 86_400_000L))
        val params = mutableListOf(
            "beginDate" to from,
            "endDate" to to,
            "page" to "1",
            "pageNum" to pageSize.toString(),
        )
        if (needStatus != null) params += "needStatus" to needStatus.toString()
        val data = call("/reserve/resvInfo", *params.toTypedArray()).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map { row ->
            val device = row.optJSONArray("resvDevInfoList")?.optJSONObject(0) ?: JSONObject()
            RoomReservation(
                resvId = row.optInt("resvId"),
                uuid = row.optString("uuid"),
                title = row.optString("testName"),
                room = device.optString("devName").ifBlank { device.optString("roomName") },
                lab = device.optString("labName"),
                begin = stamp(row.opt("resvBeginTime")),
                end = stamp(row.opt("resvEndTime")),
                status = row.optInt("resvStatus"),
            )
        }
    }

    // -- Writes --------------------------------------------------------------

    /**
     * Books [devId] for the half-open window [begin, end). The caller confirms
     * first: this consumes the room's slot and the service records misuse.
     *
     * The payload is the whole set the form posts — the same fields the service's
     * own page sends, including the applicant's account number from `auth/userInfo`.
     */
    fun book(devId: Int, begin: Date, end: Date, title: String): JSONObject {
        if (myAccNo == 0) {
            myAccNo = call("/auth/userInfo").optJSONObject("data")?.optInt("accNo") ?: 0
        }
        if (myAccNo == 0) {
            throw ApiException("room: the service did not return an account number")
        }
        val payload = JSONObject()
            .put("sysKind", RESEARCH_ROOMS)
            .put("appAccNo", myAccNo)
            .put("memberKind", 1)
            .put("resvMember", JSONArray().put(myAccNo))
            .put("resvBeginTime", IC_STAMP.format(begin))
            .put("resvEndTime", IC_STAMP.format(end))
            .put("testName", title.ifBlank { DEFAULT_TITLE })
            .put("resvProperty", 0)
            .put("resvDev", JSONArray().put(devId))
            .put("memo", "")
        return call("/reserve", body = payload, method = "POST")
    }

    /**
     * Cancels by `uuid` — the endpoint takes the uuid, not the numeric id, and the
     * cancellation is immediate and not reversible.
     */
    fun cancel(uuid: String): JSONObject {
        if (uuid.isBlank()) {
            throw ApiException("room: this reservation has no cancellation key")
        }
        return call("/reserve/delete", body = JSONObject().put("uuid", uuid), method = "POST")
    }

    // -- Plumbing ------------------------------------------------------------

    private fun call(
        path: String,
        vararg params: Pair<String, String>,
        body: JSONObject? = null,
        method: String = "GET",
        retry: Boolean = true,
    ): JSONObject {
        ensureSession()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            // Every call lives under /ic-web — without the prefix the service
            // answers its own HTML error page, which reads like a dead session.
            .addPathSegments("ic-web/" + path.trimStart('/'))
        params.forEach { url.addQueryParameter(it.first, it.second) }
        val response = request(method, url.build().toString(), body)
        if (response.optInt("code") == 0) return response
        val message = response.optString("message")
        // An expired session answers with one of these; sign in again and retry once.
        if (retry && AUTH_ERRORS.any { message.contains(it) }) {
            ensureSession(force = true)
            return call(path, *params, body = body, method = method, retry = false)
        }
        throw ApiException("room: $message")
    }

    private fun get(url: String): JSONObject = request("GET", url, null)

    private fun request(method: String, url: String, body: JSONObject?): JSONObject {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", CasLogin.UA)
            .header("Accept", "application/json, text/plain, */*")
        if (method == "POST") {
            builder.post((body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        val text = try {
            http.newCall(builder.build()).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code == 403 && payload.contains(OFF_CAMPUS_BODY)) {
                    throw ApiException(OFF_CAMPUS_HINT)
                }
                if (!response.isSuccessful) {
                    throw ApiException("room: HTTP ${response.code}")
                }
                payload
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ApiException("room: unexpected reply from the booking service")
        }
    }

    /** Unix milliseconds, or the ISO-ish strings the service sometimes echoes. */
    private fun stamp(value: Any?): String {
        val millis = when (value) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
        if (millis != null && millis > 1_000_000_000_000L) {
            return STAMP.format(Date(millis))
        }
        return value?.toString().orEmpty()
    }

    companion object {
        const val HOST = "booking.lib.sustech.edu.cn"
        private const val CAS_HOST = "cas.sustech.edu.cn"
        private const val FINAL_PAGE = "https://booking.lib.sustech.edu.cn/ic/home"
        private const val ERROR_PAGE = "https://booking.lib.sustech.edu.cn/#/error"

        /** 1 = the research/discussion rooms (讨论间). */
        const val RESEARCH_ROOMS = 1

        /** The library's rule; the service refuses anything longer. */
        const val MAX_MINUTES = 120

        /** The library's rule: bookable up to two days ahead. */
        const val MAX_DAYS_AHEAD = 2

        const val DEFAULT_TITLE = "小组讨论"

        private const val OFF_CAMPUS_BODY = "Access forbidden, please contact administrator."
        const val OFF_CAMPUS_HINT =
            "The library room service only answers on campus. Connect to campus Wi-Fi or " +
                "wired, then try again."

        private val AUTH_ERRORS = listOf("未登录", "请先登录", "session", "Authorization is")
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        /** What the service's own page posts: axios sends JSON with this type. */
        private val JSON = "application/json;charset=UTF-8".toMediaType()

        /** The wire format for booking times: "YYYY-MM-DD HH:mm:00". */
        private val IC_STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:00", Locale.US)

        /** The days the booking sheet offers, starting tomorrow. */
        fun bookableDays(): List<Date> {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return (1..MAX_DAYS_AHEAD).map { ahead ->
                val day = Calendar.getInstance()
                day.time = calendar.time
                day.add(Calendar.DAY_OF_MONTH, ahead)
                day.time
            }
        }
    }
}
