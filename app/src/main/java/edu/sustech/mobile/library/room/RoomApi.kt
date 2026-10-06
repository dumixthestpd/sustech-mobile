package edu.sustech.mobile.library.room

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.sso.CasLogin
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A room category with its right-now occupancy (讨论间, 会议室, …). */
data class RoomCategory(val name: String, val idle: Int, val total: Int)

/** One bookable room. */
data class RoomInfo(
    val devId: Int,
    val name: String,
    val minMinutes: Int,
    /** Open windows as `HH:mm–HH:mm`, usually one, sometimes morning + afternoon. */
    val windows: List<String>,
    val free: Boolean,
)

data class RoomLab(val labId: Int, val name: String, val rooms: List<RoomInfo>)

/** Rooms are grouped by campus, each holding the labs (floors) of that campus. */
data class RoomCampus(val name: String, val labs: List<RoomLab>)

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
)

/**
 * The IC library's discussion/meeting rooms (`booking.lib.sustech.edu.cn/ic-web`).
 *
 * Read-only here: booking and cancelling change a real room calendar, so they stay
 * on the official page.
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
 */
class RoomApi(private val http: OkHttpClient) {

    @Volatile
    private var signedIn = false

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

    /** Right-now occupancy per category, as the service's own home page shows it. */
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

    /** The labs (floors / areas) rooms live in, for the given category. */
    fun labs(classKind: Int = RESEARCH_ROOMS): List<RoomLab> {
        val data = call(
            "/lab/devKindLabs",
            "classKind" to classKind.toString(),
            "kindIds" to "",
        ).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map {
            RoomLab(it.optInt("labId"), it.optString("labName"), emptyList())
        }
    }

    /** Every room in a (kind, lab), grouped by campus as the service groups them. */
    fun rooms(kindId: Int, labId: Int, classKind: Int = RESEARCH_ROOMS): List<RoomCampus> {
        val data = call(
            "/roomDevice/roomInfos",
            "classKind" to classKind.toString(),
            "kindId" to kindId.toString(),
            "labId" to labId.toString(),
        ).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }.map { campus ->
            val labs = campus.optJSONArray("labInfos") ?: JSONArray()
            RoomCampus(
                name = campus.optString("campusName"),
                labs = (0 until labs.length()).mapNotNull { labs.optJSONObject(it) }.map { lab ->
                    val rooms = lab.optJSONArray("roomInfos") ?: JSONArray()
                    RoomLab(
                        labId = lab.optInt("labId"),
                        name = lab.optString("labName"),
                        rooms = (0 until rooms.length()).mapNotNull { rooms.optJSONObject(it) }
                            .map { room ->
                                val windows = room.optJSONArray("openTimes") ?: JSONArray()
                                RoomInfo(
                                    devId = room.optInt("devId"),
                                    name = room.optString("devName"),
                                    minMinutes = room.optInt("minResvTime"),
                                    windows = (0 until windows.length())
                                        .mapNotNull { windows.optJSONObject(it) }
                                        .map { "${it.optString("openStartTime")}–${it.optString("openEndTime")}" },
                                    // Populated exactly when the room is taken.
                                    free = (room.optJSONArray("resvInfos")?.length() ?: 0) == 0,
                                )
                            },
                    )
                },
            )
        }
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

    // -- Plumbing ------------------------------------------------------------

    private fun call(path: String, vararg params: Pair<String, String>, retry: Boolean = true): JSONObject {
        ensureSession()
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(HOST)
            // Every read lives under /ic-web — without it the service answers its
            // own HTML error page, which looks like a dead session.
            .addPathSegments("ic-web/" + path.trimStart('/'))
        params.forEach { url.addQueryParameter(it.first, it.second) }
        val body = get(url.build().toString())
        if (body.optInt("code") == 0) return body
        val message = body.optString("message")
        // An expired session answers with one of these; sign in again and retry once.
        if (retry && AUTH_ERRORS.any { message.contains(it) }) {
            ensureSession(force = true)
            return call(path, *params, retry = false)
        }
        throw ApiException("room: $message")
    }

    private fun get(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", CasLogin.UA)
            .header("Accept", "application/json, text/plain, */*")
            .build()
        val body = try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 403 && text.contains(OFF_CAMPUS_BODY)) {
                    throw ApiException(OFF_CAMPUS_HINT)
                }
                if (!response.isSuccessful) {
                    throw ApiException("room: HTTP ${response.code}")
                }
                text
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        return try {
            JSONObject(body)
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

        private const val OFF_CAMPUS_BODY = "Access forbidden, please contact administrator."
        const val OFF_CAMPUS_HINT =
            "The library room service only answers on campus. Connect to campus Wi-Fi or " +
                "wired, then try again."

        private val AUTH_ERRORS = listOf("未登录", "请先登录", "session", "Authorization is")
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    }
}
