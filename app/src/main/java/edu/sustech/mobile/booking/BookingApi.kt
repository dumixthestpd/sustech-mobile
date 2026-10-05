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
 * The service is campus-network only, as the platform itself is.
 */
class BookingApi(private val http: OkHttpClient) {

    @Volatile
    private var token: String? = null

    fun rooms(page: Int = 1, rows: Int = 500, keyword: String? = null): List<BookingRoom> {
        val data = JSONObject().put("page", page).put("rows", rows)
        val response = call(METHOD_ROOMS, data)
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
        val data = JSONObject().put("page", 1).put("rows", 100)
        val response = call(METHOD_MY_MEETINGS, data)
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
        messageType: Int = TYPE_CALL,
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
            .header("accept", "application/json, text/javascript, */*; q=0.01")
            .header("x-requested-with", "XMLHttpRequest")
            .apply { auth?.let { header("authorization", it) } }
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
                        httpStatus = response.code,
                    )
                }
                // Read once — a response body is a single-use stream.
                response.body?.string().orEmpty()
            }
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
        private const val TYPE_PROFILE = 1001
        private const val TYPE_CALL = 1002
        private val JSON = "application/json; charset=utf-8".toMediaType()
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
