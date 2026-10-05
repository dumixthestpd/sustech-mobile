package edu.sustech.mobile.ws

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.sso.CasLogin
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One exchange programme as the listing returns it. */
data class WsProgram(
    val id: String,
    val name: String,
    val region: String,
    val school: String,
    val type: String,
    val applyRange: String,
    val status: String,
    val appliable: Boolean,
    /** Needed to open this programme's official detail page. */
    val code: String,
    val tokenKey: String,
)

data class WsProgramPage(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val programs: List<WsProgram>,
)

/**
 * 外事信息系统 (`ws.sustech.edu.cn`) exchange programmes.
 *
 * This used to be handed to a WebView, which then asked the user to sign in a
 * second time even though the app already holds the CAS account. The listing is
 * ordinary JSON once the service session exists: redeeming the CAS ticket sets
 * the WS cookies, the left-menu endpoint yields the per-session `userToken`, and
 * the programme list is paged JSON.
 *
 * Only reads live here. Applying is a form-heavy flow on the official page, so a
 * programme opens there — now with the session already established instead of a
 * login form.
 */
class WsApi(private val http: OkHttpClient) {

    @Volatile
    private var token: String? = null

    @Volatile
    private var ts: String? = null

    fun programs(page: Int = 1, pageSize: Int = 24): WsProgramPage = try {
        read(page, pageSize)
    } catch (e: ApiException) {
        // The per-session token goes stale with the cookies; re-establish once
        // rather than showing a sign-in error to someone who is signed in.
        if (!e.signInRequired) throw e
        token = null
        ts = null
        read(page, pageSize)
    }

    private fun read(page: Int, pageSize: Int): WsProgramPage {
        val (userToken, tsValue) = session()
        val body = get(
            "$BASE/StudentExchange_2247/GetShortProjectListForStudent.do" +
                "?pageSize=$pageSize&currentPageIndex=$page&ts=$tsValue&userToken=$userToken",
        )
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            // The service answers a stale token with an HTML error page, not JSON.
            throw ApiException("ws: unexpected response", signInRequired = true)
        }
        val rows = json.optJSONArray("DataList") ?: JSONArray()
        val programs = (0 until rows.length()).map { index -> program(rows.optJSONObject(index)) }
        return WsProgramPage(
            total = json.optInt("RecordCount", programs.size),
            page = json.optInt("CurrentPageIndex", page),
            pageSize = json.optInt("PageSize", pageSize),
            programs = programs,
        )
    }

    private fun program(row: JSONObject?): WsProgram {
        val o = row ?: JSONObject()
        fun text(vararg keys: String): String {
            for (key in keys) {
                val raw = o.optString(key)
                if (raw.isNotBlank() && raw != "null") return WsText.decode(raw)
            }
            return ""
        }
        return WsProgram(
            id = o.optString("ID"),
            name = text("Name"),
            region = text("RegionName"),
            school = text("ProjectSchoolName", "ProjectAgencyName"),
            type = text("ProjectTypeText"),
            applyRange = text("ApplyRangeText"),
            status = text("StudentExchangeProjectStatusIDText", "IsValidTextByEndDate"),
            appliable = o.optInt("IsAppliable", 0) > 0,
            code = o.optString("Code"),
            tokenKey = o.optString("TokenKey"),
        )
    }

    /** The listing's `userToken`/`ts`, taken from the exchange page in the menu. */
    private fun session(): Pair<String, String> {
        token?.let { cached -> ts?.let { return cached to it } }
        ensureSignedIn()
        val menu = get("$BASE/Main/GetSmartLeftMenuTData.do")
        val pair = WsText.sessionToken(menu)
            ?: throw ApiException("ws: no session token in the menu", signInRequired = true)
        token = pair.first
        ts = pair.second
        return pair
    }

    private fun ensureSignedIn() {
        val probe = Request.Builder()
            .url(HOME)
            .header("User-Agent", CasLogin.UA)
            .build()
        val needsLogin = try {
            http.newCall(probe).execute().use { response ->
                response.code == 401 || response.code == 403 ||
                    (response.isRedirect && response.header("Location").orEmpty().contains(CAS_HOST))
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        if (!needsLogin) return
        if (!Credentials.configured) {
            throw ApiException("ws: no stored account", signInRequired = true)
        }
        CasLogin.login(HOME, Credentials.sid, Credentials.password, xhr = false)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CasLogin.UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", HOME)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401 || response.code == 403 || response.isRedirect) {
                    throw ApiException("ws: not signed in", signInRequired = true)
                }
                if (!response.isSuccessful) {
                    throw ApiException("ws: HTTP ${response.code}", httpStatus = response.code)
                }
                // Read once: a response body is a single-use stream.
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }

    companion object {
        const val BASE = "https://ws.sustech.edu.cn"
        const val HOME = "$BASE/SUSTechHome.aspx"
        private const val CAS_HOST = "cas.sustech.edu.cn"

        /** The official detail page for a programme, session already carried by the cookies. */
        fun detailUrl(program: WsProgram): String =
            "$BASE/StudentExchange_2247/ProjectDetail2247.do?ID=${program.id}" +
                "&Code=${program.code}&token=${program.tokenKey}"
    }
}

/**
 * The two pieces of WS text handling worth testing on their own, with no Android
 * types involved: pulling the session token out of the menu payload (the menu
 * lists several modules, and the first one is not the exchange page) and decoding
 * the HTML entities the listing embeds in names.
 */
object WsText {

    private val EXCHANGE_TOKEN = Regex(
        """StudentExchange_2247/ProjectList\d*\.do\?[^"']*?ts=(\d+)[^"']*?userToken=([0-9A-Fa-f]+)""",
    )
    private val ANY_TOKEN = Regex("""ts=(\d+)[^"']*?userToken=([0-9A-Fa-f]+)""")
    private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
    private val HEX_ENTITY = Regex("""&#[xX]([0-9A-Fa-f]+);""")
    private val NAMED_ENTITIES = mapOf(
        "&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">",
        "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'",
    )

    /** Returns `userToken to ts`, preferring the exchange module's own page. */
    fun sessionToken(menuJson: String): Pair<String, String>? {
        val match = EXCHANGE_TOKEN.find(menuJson) ?: ANY_TOKEN.find(menuJson) ?: return null
        val ts = match.groupValues[1]
        val token = match.groupValues[2]
        if (token.isEmpty()) return null
        return token to ts
    }

    /** Decodes the numeric and common named entities the listing returns. */
    fun decode(raw: String): String {
        if (raw.isEmpty()) return raw
        var out = raw
        out = HEX_ENTITY.replace(out) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        out = NUMERIC_ENTITY.replace(out) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        NAMED_ENTITIES.forEach { (entity, replacement) -> out = out.replace(entity, replacement) }
        return out.trim()
    }
}
