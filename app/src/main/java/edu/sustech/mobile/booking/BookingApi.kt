package edu.sustech.mobile.booking

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.sso.CasLogin
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A bookable venue. */
data class BookingRoom(
    val id: String,
    val name: String,
    val type: String,
    val capacity: Int,
    val location: String,
    val available: Boolean,
    val approvalRequired: Boolean,
    val daysAhead: Int,
    val bookStart: String,
    val bookEnd: String,
)

/** One of my reservations. */
data class BookingMeeting(
    val id: String,
    val roomName: String,
    val title: String,
    val startAt: String,
    val endAt: String,
    val status: String,
)

/** One fixed-length interval in a venue's bookable window. */
data class BookingSlot(
    val start: String,
    val end: String,
    val available: Boolean,
    val reason: String = "",
    val meetingTitle: String = "",
    val live: Boolean = true,
)

/**
 * Venue booking (`booking.sustech.edu.cn`) — the system behind the E-Hall venue
 * page the app used to hand to a WebView.
 *
 * The handshake is: CAS ticket → `GetUserProfile` (message type 1001, carrying
 * the ticket as `St`) → a bearer token, which every later call sends in a plain
 * `authorization` header. Reads only for now: creating and cancelling a
 * reservation are writes against a real room calendar, so they stay on the
 * official page until they can be previewed and read back here.
 *
 * The service is publicly reachable; authentication is handled through the
 * public CAS redirect and the booking service's bearer-token handshake.
 */
class BookingApi(private val http: OkHttpClient) {

    @Volatile
    private var token: String? = null

    fun rooms(page: Int = 1, rows: Int = 500, keyword: String? = null): List<BookingRoom> {
        // Keep this in step with the current E-Hall web client. The old
        // page/rows envelope is accepted by neither the current service nor
        // its public web UI and can lead to an empty/404-looking result.
        val data = JSONObject()
            .put("deptid", "")
            .put("MeetingRoomType", JSONObject.NULL)
            .put("search", keyword.orEmpty())
            .put("limit", rows)
            .put("offset", (page - 1).coerceAtLeast(0) * rows)
        val response = call(METHOD_ROOMS, data, messageType = TYPE_ROOM_LIST)
        val array = rowsOf(response)
        val rooms = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .map(::room)
        val needle = keyword.orEmpty().trim().lowercase(Locale.US)
        if (needle.isEmpty()) return rooms
        return rooms.filter { room ->
            listOf(room.name, room.id, room.type, room.location, room.capacity.toString())
                .any { it.lowercase(Locale.US).contains(needle) }
        }
    }

    fun meetings(): List<BookingMeeting> {
        // GetMyMeetings is the web client's reservation list endpoint. Its
        // payload is a filter object, not the legacy page/rows shape.
        val data = JSONObject()
            .put("deptid", JSONObject.NULL)
            .put("MeetingRoomType", JSONObject.NULL)
            .put("RequestTypes", JSONObject.NULL)
            .put("StartDate", JSONObject.NULL)
            .put("EndDate", JSONObject.NULL)
            .put("States", JSONObject.NULL)
            .put("ParticipantStates", JSONObject.NULL)
            .put("OptionType", JSONObject.NULL)
            .put("search", "")
            .put("limit", 100)
            .put("sort", "")
            .put("order", "")
            .put("offset", 0)
        val response = call(METHOD_MY_MEETINGS, data, messageType = TYPE_MY_MEETINGS)
        val array = rowsOf(response)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map(::meeting)
    }

    /** Build a live available/unavailable map for one room and Shanghai day. */
    fun roomSlots(room: BookingRoom, date: String, slotMinutes: Long = 60): List<BookingSlot> {
        require(slotMinutes > 0) { "slotMinutes must be positive" }
        val day = LocalDate.parse(date)
        val start = parseTime(room.bookStart) ?: return emptyList()
        val end = parseTime(room.bookEnd) ?: return emptyList()
        // The current public service exposes the room list successfully but
        // answers the room-calendar route with HTTP 404. Keep the slot view
        // useful and honest in that case: show the configured bookable window
        // as an estimate, while the UI labels it as not live-verified.
        val liveMeetings = try {
            roomCalendar(room.id, day)
        } catch (error: ApiException) {
            if (error.httpStatus == 404) null else throw error
        }
        val meetings = liveMeetings.orEmpty()
        val slots = mutableListOf<BookingSlot>()
        var cursor = day.atTime(start)
        val windowEnd = day.atTime(end)
        while (!cursor.plusMinutes(slotMinutes).isAfter(windowEnd)) {
            val slotEnd = cursor.plusMinutes(slotMinutes)
            val overlap = meetings.firstOrNull { item ->
                val meetingStart = parseDateTime(item.startAt)
                val meetingEnd = parseDateTime(item.endAt)
                meetingStart != null && meetingEnd != null &&
                    meetingStart.isBefore(slotEnd) && meetingEnd.isAfter(cursor)
            }
            slots += BookingSlot(
                start = cursor.toLocalTime().format(CLOCK),
                end = slotEnd.toLocalTime().format(CLOCK),
                available = room.available && overlap == null,
                reason = when {
                    liveMeetings == null -> "calendar_unavailable"
                    !room.available -> "room_unavailable"
                    overlap != null -> "occupied"
                    else -> ""
                },
                meetingTitle = overlap?.title.orEmpty(),
                live = liveMeetings != null,
            )
            cursor = slotEnd
        }
        return slots
    }

    private fun roomCalendar(roomId: String, day: LocalDate): List<BookingMeeting> {
        val response = call(
            METHOD_ROOM_CALENDAR,
            JSONObject().put("meetingroomid", roomId)
                // The web client sends a JavaScript Date through JSON. The
                // service also accepts the equivalent local Shanghai value;
                // use that representation for the legacy calendar handler.
                .put("currentdate", "${day}T00:00:00+08:00"),
            messageType = TYPE_ROOM_CALENDAR,
        )
        val array = rowsOf(response)
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map(::meeting)
    }

    private fun rowsOf(response: JSONObject): JSONArray =
        response.optJSONObject("Data")?.optJSONArray("rows")
            ?: response.optJSONArray("rows")
            ?: JSONArray()

    private fun room(raw: JSONObject) = BookingRoom(
        id = raw.optString("MeetingRoomID"),
        name = raw.optString("MeetingRoomName"),
        type = raw.optString("MeetingRoomType"),
        capacity = raw.optInt("CapacityNumber"),
        location = raw.optString("MeetingRoomLocal"),
        available = raw.optBoolean("IsAvailable"),
        approvalRequired = raw.optBoolean("IsApproval"),
        daysAhead = raw.optInt("NumberOfDaysAhead"),
        bookStart = timeText(raw.optString("CanBookStartTime")),
        bookEnd = timeText(raw.optString("CanBookEndTime")),
    )

    private fun meeting(raw: JSONObject) = BookingMeeting(
        id = raw.optString("MeetingID").ifBlank { raw.optString("ID") },
        roomName = raw.optString("MeetingRoomName"),
        title = text(raw, "MeetingName", "Title", "Topical"),
        startAt = text(raw, "StartTime", "MeetingStart", "StartDateTime"),
        endAt = text(raw, "EndTime", "MeetingEnd", "EndDateTime"),
        status = text(raw, "Status", "MeetingStatus", "State"),
    )

    private fun text(raw: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = raw.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun timeText(value: String): String =
        Regex("T(\\d{2}:\\d{2}:\\d{2})").find(value)?.groupValues?.get(1) ?: value

    private fun parseTime(value: String): LocalTime? = runCatching {
        LocalTime.parse(value.take(8), DateTimeFormatter.ofPattern("HH:mm:ss"))
    }.getOrNull() ?: runCatching {
        LocalTime.parse(value.take(5), DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrNull()

    private fun parseDateTime(value: String): LocalDateTime? {
        val raw = value.trim()
        if (raw.isBlank()) return null
        return runCatching {
            OffsetDateTime.parse(raw).withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime()
        }.getOrNull() ?: runCatching {
            LocalDateTime.parse(raw.replace(" ", "T").take(19))
        }.getOrNull()
    }

    /**
     * The bearer token, obtained from the CAS ticket exactly once per session.
     *
     * Synchronised because the screen's two tabs load at the same moment: without
     * it, each one runs the whole CAS handshake and the service sees a burst of
     * sign-ins from one session.
     */
    @Synchronized
    private fun token(): String {
        token?.let { return it }
        if (!Credentials.configured) {
            throw ApiException("booking: no stored account", signInRequired = true)
        }
        val ticketUrl = CasLogin.loginForTicket(
            SERVICE, Credentials.sid, Credentials.password, xhr = false,
        )
        val ticket = ticketUrl.toHttpUrlOrNull()?.queryParameter("ticket")
            ?: throw ApiException("booking: CAS returned no ticket")
        val handshake = JSONObject().put("Url", SERVICE).put("St", ticket)
        val response = call(METHOD_PROFILE, handshake, messageType = TYPE_PROFILE, allowRetry = false)
        val value = response.optJSONObject("Data")?.optString("Token").orEmpty()
        if (value.isBlank()) {
            throw ApiException("booking: profile handshake returned no token", signInRequired = true)
        }
        token = value
        return value
    }

    private fun call(
        method: String,
        data: JSONObject,
        messageType: Int = TYPE_ROOM_LIST,
        allowRetry: Boolean = true,
    ): JSONObject {
        val auth = if (messageType == TYPE_PROFILE) null else token()
        val body = BookingWire.envelope(data.toString(), messageType, BookingWire.newMessageId())
        val request = Request.Builder()
            .url("$BASE/api/SystemApi/$method")
            .post(body.toRequestBody(JSON))
            // 🔴 The service answers a non-browser User-Agent with 502, not 403:
            // OkHttp's default "okhttp/4.x" is refused by whatever fronts it.
            .header("User-Agent", CasLogin.UA)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("x-requested-with", "XMLHttpRequest")
            // Match the browser client closely. The gateway is nominally
            // case-insensitive, but its legacy calendar handler has been
            // observed to route differently when these browser headers are
            // absent.
            .header("Origin", BASE)
            .header("Referer", "$BASE/")
            .header("Language", Locale.getDefault().language)
            .header("Access-Control-Allow-Origin", "*")
            .header("Access-Control-Allow-Headers", "X-Requested-With,Content-Type")
            .header("Access-Control-Allow-Methods", "PUT,POST,GET,DELETE,OPTIONS")
            .apply { auth?.let { header("Authorization", it) } }
            .build()

        val text = try {
            http.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    throw ApiException("booking: session rejected", signInRequired = true)
                }
                if (!response.isSuccessful) {
                    // Keep what the server said, and which call it was: a bare
                    // status code is unactionable.
                    val detail = response.body?.string().orEmpty()
                        .replace(Regex("\\s+"), " ").trim().take(120)
                    throw ApiException(
                        "booking: $method HTTP ${response.code}" +
                            (if (detail.isEmpty()) "" else " · $detail"),
                        signInRequired = auth != null && response.code in setOf(401, 403, 404),
                        httpStatus = response.code,
                    )
                }
                // Read once — a response body is a single-use stream.
                response.body?.string().orEmpty()
            }
        } catch (e: ApiException) {
            // The service uses HTTP 404 for some unauthorised room-calendar
            // requests. Treat it like a stale bearer token once, then expose
            // the real status to the screen if the renewed session is also
            // rejected.
            if (allowRetry && auth != null && e.httpStatus in setOf(401, 403, 404)) {
                token = null
                return call(method, data, messageType, allowRetry = false)
            }
            throw e
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }

        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ApiException("booking: unexpected response", signInRequired = auth != null)
        }
        if (json.optBoolean("IsSuccess") != true || BookingWire.looksLikeAuthError(text)) {
            // A stale token is the common case here, so re-sign in once rather
            // than telling someone who is signed in to sign in.
            if (allowRetry && auth != null) {
                token = null
                return call(method, data, messageType, allowRetry = false)
            }
            val reason = json.optString("Message").takeIf { it.isNotBlank() && it != "null" }
            throw ApiException(
                "booking: ${reason ?: "the request was rejected"}",
                signInRequired = auth != null || messageType == TYPE_PROFILE,
            )
        }
        return json
    }

    companion object {
        const val BASE = "https://booking.sustech.edu.cn"

        /** The CAS entry point this service registers. */
        const val SERVICE = "$BASE/redirect"

        private const val METHOD_PROFILE = "GetUserProfile"
        private const val METHOD_ROOMS = "GetMeetingRoomAllByCondition"
        private const val METHOD_MY_MEETINGS = "GetMyMeetings"
        private const val METHOD_ROOM_CALENDAR = "GetMeetingByMeetingRoomList"
        private const val TYPE_PROFILE = 1001
        private const val TYPE_ROOM_LIST = 5004
        private const val TYPE_MY_MEETINGS = 5005
        private const val TYPE_ROOM_CALENDAR = 5001
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/**
 * The request envelope, kept separate from the HTTP client so it can be tested
 * without an Android runtime: every booking call is a POST body of this exact
 * shape, and getting it wrong reads as a server-side rejection.
 */
object BookingWire {

    fun newMessageId(): String = UUID.randomUUID().toString()

    fun envelope(dataJson: String, messageType: Int, messageId: String): String =
        """{"MessageType":$messageType,"MessageID":"$messageId","Data":$dataJson}"""

    private val AUTH_ERRORS = listOf(
        "Authorization is NULL",
        "Authorization is invalid",
        "未登录",
        "请先登录",
    )

    fun looksLikeAuthError(body: String): Boolean = AUTH_ERRORS.any { body.contains(it) }
}
