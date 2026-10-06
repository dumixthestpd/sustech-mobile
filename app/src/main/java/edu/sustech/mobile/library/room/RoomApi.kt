package edu.sustech.mobile.library.room

import android.text.Html
import edu.sustech.mobile.R
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
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A room category with its right-now occupancy (讨论间, 会议室, …). */
data class RoomCategory(val name: String, val idle: Int, val total: Int)

/** One bookable room, with where it is and what it takes to book it. */
data class RoomInfo(
    val devId: Int,
    val name: String,
    val lab: String,
    /** The capacity the service put in the name, e.g. `3-6人`; empty when unnamed. */
    val people: String,
    /** True for rooms whose *minimum* capacity is 3+: they need co-applicants. */
    val needsMembers: Boolean,
    /** The service's minimum session length in minutes (10 for the rooms measured). */
    val minMinutes: Int,
    val free: Boolean,
    /**
     * True for equipment lending (设备外借) rather than a room — the studio, the 3D
     * printer, the book scanner. The service hands them over in the same flat inventory
     * with **no field saying so**, so this is derived: they are the labs that carry
     * exactly one device, named after the lab itself, while real rooms sit several to a
     * floor.
     *
     * 🔴 They take a different booking form (a purpose from code table 1005, date, start
     * and end, a memo, **and a captcha**) and post to a different endpoint, so the room
     * sheet must never be used for one of them.
     */
    val lending: Boolean = false,
)

/** Someone this account can name as a co-applicant. */
data class RoomMember(
    val accNo: Int,
    val sid: String,
    val name: String,
    /** The service marks accounts that cannot be booked with (2 = unavailable). */
    val unavailable: Boolean,
)

/** One window the service allows on a given day. */
data class TimeWindow(val begin: Date, val end: Date, val label: String)

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
 * Also **not** campus-only: library room booking works off campus (owner, 2026-10-06).
 * A 403 with `Access forbidden, please contact administrator.` is the service's own
 * refusal of that request, reported as such — never as "join the campus network".
 *
 * Booking rules the service enforces (from the library's 讨论间使用办法, shown in the
 * UI rather than guessed at): up to 2 days ahead, at most 2 hours per booking, and a
 * room whose minimum capacity is 3+ needs **the booker plus two co-applicants** —
 * rooms named `（1-3人）` do not, rooms named `（3-6人）` do. Co-applicants are named by
 * student id and resolved to account numbers through the service's own member lookup.
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
     * These counts are per category and carry **no ids**, so they summarise rather
     * than match [allRooms]; the numbers legitimately differ.
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
                    val name = room.optString("devName")
                    rooms += RoomInfo(
                        devId = room.optInt("devId"),
                        name = name,
                        lab = labName,
                        people = peopleLabel(name),
                        needsMembers = needsCoApplicants(name),
                        minMinutes = room.optInt("minResvTime"),
                        // Populated exactly when the room is taken.
                        free = (room.optJSONArray("resvInfos")?.length() ?: 0) == 0,
                        lending = isLending(labName, name, roomInfos.length()),
                    )
                }
            }
        }
        return rooms.sortByFloor()
    }

    /** How many reservations this account holds. */
    fun count(): Int = call("/reserve/count").optInt("data")

    /**
     * The library's own policy text, from its help page —
     * `GET /sysInfo/help?sysType=16&sysKind=4&status=2&sysValue=`, the one sysKind whose
     * reply is text rather than a banner image. This is what the ⓘ shows: the library's
     * words, not this app's summary of them. Null when the service declines.
     *
     * 🔴 The reply is a list of rows whose `content` is **HTML** — rendering it raw put
     * `<p class=…>` on screen, so it goes through `Html.fromHtml` first (which also
     * decodes the entities).
     */
    fun policy(): String? {
        val body = call(
            "/sysInfo/help",
            "sysType" to "16",
            "sysKind" to "4",
            "status" to "2",
            "sysValue" to "",
        )
        val rows = body.optJSONArray("data")
        val html = if (rows != null) {
            (0 until rows.length())
                .mapNotNull { rows.optJSONObject(it)?.optString("content") }
                .firstOrNull { it.isNotBlank() }
        } else {
            body.optString("data").ifBlank { body.optString("message") }
        }
        val text = html?.let {
            Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString()
        }
        return text?.trim()?.ifBlank { null }
    }

    /**
     * The windows the service allows on [day] (`borrow/reserve/timeScope?beginDate=`,
     * the call its page makes when a date is picked — export "d" of its api module).
     * **The service decides which days are bookable**: null means it stated no scope
     * (unknown, so nothing is gated on it), an empty list means it stated none.
     */
    fun timeScope(day: Date): List<TimeWindow>? {
        // `data: null` is the service declining to state a scope, not stating there is
        // none — its own page renders whatever it gets. So: null = unknown (do not gate
        // on it), an array = its answer, empty array = it lists nothing that day.
        val reply = call("/borrow/reserve/timeScope", "beginDate" to SCOPE_DAY.format(day))
        val data = reply.optJSONArray("data") ?: return null
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { row ->
            val begin = scopeStamp(row.optString("beginTime"), day) ?: return@mapNotNull null
            val end = scopeStamp(row.optString("endTime"), day) ?: return@mapNotNull null
            TimeWindow(begin, end, "${CLOCK.format(begin)}–${CLOCK.format(end)}")
        }
    }

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

    /**
     * Finds a co-applicant by student id (or name) using the service's own member
     * lookup — the same call the page's picker makes: `account/getMembers?key=`.
     *
     * Returns null when nothing matches, so the caller can refuse *before* sending.
     */
    fun findMember(sid: String): RoomMember? = searchMembers(sid)
        .firstOrNull { it.sid.equals(sid, ignoreCase = true) || it.name == sid }
        ?: searchMembers(sid).firstOrNull()

    /** Members matching [key] (student id or name). */
    fun searchMembers(key: String): List<RoomMember> {
        val data = call(
            "/account/getMembers",
            "key" to key,
            "page" to "1",
            "pageNum" to "10",
        ).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.mapNotNull { row ->
            val accNo = row.optInt("accNo")
            if (accNo == 0) return@mapNotNull null
            RoomMember(
                accNo = accNo,
                sid = row.optString("logonName"),
                name = row.optString("trueName"),
                unavailable = row.optInt("status") == 2 || row.optInt("localstatus") == 2,
            )
        }
    }

    // -- Writes --------------------------------------------------------------

    /**
     * Books [devId] for the half-open window [begin, end).
     *
     * [coApplicants] are account numbers (resolve student ids with [findMember]);
     * passing any switches the booking to a group one (`memberKind = 2`), which is
     * what a room whose minimum capacity is 3+ requires — the service refuses it
     * otherwise.
     */
    fun book(
        devId: Int,
        begin: Date,
        end: Date,
        title: String,
        coApplicants: List<Int> = emptyList(),
    ): JSONObject {
        val me = myAccNo()
        val members = listOf(me) + coApplicants.filter { it != me }.distinct()
        val payload = JSONObject()
            .put("sysKind", RESEARCH_ROOMS)
            .put("appAccNo", me)
            .put("memberKind", if (members.size > 1) 2 else 1)
            .put("resvMember", JSONArray(members))
            .put("resvBeginTime", IC_STAMP.format(begin))
            .put("resvEndTime", IC_STAMP.format(end))
            .put("testName", title.ifBlank { DEFAULT_TITLE })
            .put("resvKind", 2)
            .put("resvProperty", 0)
            .put("appUrl", "")
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

    private fun myAccNo(): Int {
        if (myAccNo == 0) {
            myAccNo = call("/auth/userInfo").optJSONObject("data")?.optInt("accNo") ?: 0
        }
        if (myAccNo == 0) {
            throw ApiException("room: the service did not return an account number")
        }
        return myAccNo
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
            // The service's own client sends these on every call.
            .header("X-Requested-With", "XMLHttpRequest")
        if (method == "POST") {
            builder.post((body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        val text = try {
            http.newCall(builder.build()).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.code == 403 && payload.contains(REFUSED_BODY)) {
                    throw ApiException(App.context.getString(R.string.rooms_refused))
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

        /** What the service's own menu calls this family. */
        const val RESEARCH_ROOMS = 1

        /**
         * Where the families the app does not book natively are handed over — the SPA's
         * entry, which is the one route it will render: `/ic/deviceLending` and the other
         * routes answer "error page!" even with a live IC session (measured 2026-10-06,
         * both ways), so the section is one tap from here rather than deep-linked.
         * ServicePortalActivity establishes the IC session before loading it.
         */
        const val LENDING_PAGE = "https://booking.lib.sustech.edu.cn/"

        /** The library's rule; the service refuses anything longer. */
        const val MAX_MINUTES = 120

        /** The library's rule: bookable up to two days ahead. */
        const val MAX_DAYS_AHEAD = 2

        /** A 3+ person room needs the booker plus this many co-applicants. */
        const val MIN_CO_APPLICANTS = 2

        /**
         * A 3+ person room needs this many campus cards scanned at the room's screen to
         * count as checked in; fewer means a rule violation and a week's booking ban for
         * the booker (policy 1.4).
         */
        const val MIN_SCAN_CARDS = 3

        const val DEFAULT_TITLE = "小组讨论"

        /**
         * The service's own refusal, shown as-is. 🔴 This is **not** a location error:
         * library room booking works off campus (owner, 2026-10-06). Do not tell anyone
         * to join the campus network for it.
         */
        private const val REFUSED_BODY = "Access forbidden, please contact administrator."

        private val AUTH_ERRORS = listOf("未登录", "请先登录", "session", "Authorization is")
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        /** What the service's own page posts: axios sends JSON with this type. */
        private val JSON = "application/json;charset=UTF-8".toMediaType()

        /** The wire format for booking times: "YYYY-MM-DD HH:mm:00". */
        private val IC_STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:00", Locale.US)

        private val CAPACITY = Regex("（(\\d+)-(\\d+)人）")
        private val CAPACITY_MIN = Regex("（(\\d+)人以上）")
        private val CAPACITY_LABEL = Regex("（[^）]*人[^）]*）")
        private val FLOOR_ORDER = Regex("^(\\D+)")

        /**
         * Equipment lending arrives in the same flat inventory as rooms and carries no
         * marker saying so, so it is derived: a lab holding exactly one device, named
         * after the lab itself. A floor holds several and is never named after one of
         * them, so this separates them without a name list to maintain.
         */
        fun isLending(labName: String, deviceName: String, devicesInLab: Int): Boolean =
            devicesInLab == 1 && labName == deviceName

        /**
         * True when the service's own name says the room starts at 3+ people, which
         * is what triggers the co-applicant rule (policy 1.3). `（1-3人）` is not one:
         * the check is on the **lower** bound, mirroring the Python client.
         */
        fun needsCoApplicants(name: String): Boolean {
            CAPACITY.find(name)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) >= 3 }
            CAPACITY_MIN.find(name)?.let { return (it.groupValues[1].toIntOrNull() ?: 0) >= 3 }
            return false
        }

        private fun peopleLabel(name: String): String =
            CAPACITY_LABEL.find(name)?.value?.trim('（', '）').orEmpty()

        /** Floors in reading order, then by room name, so the list is not criss-cross. */
        private fun List<RoomInfo>.sortByFloor(): List<RoomInfo> = sortedWith(
            compareBy({ FLOOR_ORDER.find(it.lab)?.value.orEmpty() }, { it.name }),
        )

        /**
         * The days the sheet offers: **today through [MAX_DAYS_AHEAD] days out**. Today
         * is offered because the service accepts it — "up to two days ahead" bounds how
         * far out, not the same day. What each day allows is the service's own answer to
         * [timeScope], so this list does not decide.
         */
        fun bookableDays(): List<Date> {
            val calendar = Calendar.getInstance()
            calendar.set(Calendar.HOUR_OF_DAY, 0)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return (0..MAX_DAYS_AHEAD).map { ahead ->
                val day = Calendar.getInstance()
                day.time = calendar.time
                day.add(Calendar.DAY_OF_MONTH, ahead)
                day.time
            }
        }

        /** The service's own date parameter for the day's scope: `beginDate=YYYYMMDD`. */
        private val SCOPE_DAY = SimpleDateFormat("yyyyMMdd", Locale.US)

        private val CLOCK = SimpleDateFormat("HH:mm", Locale.US)

        /**
         * A `beginTime`/`endTime` from the scope reply. The service may send a full
         * datetime or only a clock time, in which case it belongs to [day].
         */
        internal fun scopeStamp(value: String, day: Date): Date? {
            val text = value.trim()
            if (text.isEmpty()) return null
            val clean = text.replace('T', ' ').substringBefore('+')
            for (format in DATE_FORMATS) {
                try {
                    return SimpleDateFormat(format, Locale.US).parse(clean)
                } catch (e: ParseException) {
                    // Not this one; the clock-time fallback below covers the rest.
                }
            }
            val clock = Regex("(\\d{1,2}):(\\d{2})").find(text) ?: return null
            val calendar = Calendar.getInstance()
            calendar.time = day
            calendar.set(Calendar.HOUR_OF_DAY, clock.groupValues[1].toInt())
            calendar.set(Calendar.MINUTE, clock.groupValues[2].toInt())
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.time
        }

        private val DATE_FORMATS = listOf(
            "yyyy/MM/dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy/MM/dd HH:mm",
            "yyyy-MM-dd HH:mm",
            "yyyyMMdd HH:mm:ss",
            "yyyyMMdd HH:mm",
        )
    }
}
