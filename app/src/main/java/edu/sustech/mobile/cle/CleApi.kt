package edu.sustech.mobile.cle

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Credentials
import edu.sustech.mobile.sso.CasLogin
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** One tutoring reservation this account holds. */
data class CleReservation(
    val id: String,
    val teacher: String,
    val serviceType: String,
    val room: String,
    val date: String,
    val time: String,
    val topic: String,
    val status: String,
)

/** What the account may still book this semester. */
data class CleQuota(val used: Int, val allowed: Int)

/**
 * Language-centre tutoring (`ehall.sustech.edu.cn/dxggyw/sys/yyzxyy`, "CLE").
 *
 * 🔴 This is the one E-Hall app that a CAS ticket alone cannot read: its data
 * endpoints answer **403** until the portal's own JavaScript adapter (EMAP) has
 * run. So the session has two halves — a WebView loads the app index once to run
 * that adapter, its cookies are copied into this app's jar, and only then do the
 * `/modules/fwyy/<MODEL>.do` queries answer. `bootstrap()` performs the first
 * half; the caller owns the WebView.
 *
 * Reads only: booking posts the reservation model's entire control set and
 * consumes one of three per-semester slots, so it stays on the official page.
 */
class CleApi(private val http: OkHttpClient) {

    @Volatile
    var bootstrapped: Boolean = false
        private set

    /** Marks the session usable once the caller's WebView has run the adapter. */
    fun markBootstrapped() {
        bootstrapped = true
    }

    /**
     * Makes sure the E-Hall session exists in the cookie jar, so the bootstrap
     * WebView can load the app without showing a login form.
     */
    fun ensureSession() {
        val probe = Request.Builder()
            .url(APP_INDEX)
            .header("User-Agent", CasLogin.UA)
            .build()
        val needsLogin = try {
            http.newCall(probe).execute().use { response ->
                response.code == 401 || response.code == 403 || response.isRedirect
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        if (!needsLogin) return
        if (!Credentials.configured) {
            throw ApiException("cle: no stored account", signInRequired = true)
        }
        CasLogin.login(APP_INDEX, Credentials.sid, Credentials.password, xhr = false)
    }

    fun reservations(): List<CleReservation> =
        rows(MODEL_RESERVATION).map { row ->
            CleReservation(
                id = text(row, "WID", "ID", "YYID"),
                teacher = text(row, "JSRXM", "JSXM", "TeacherName"),
                serviceType = text(row, "FWZYMXMC", "FWZYMC", "ServiceName"),
                room = text(row, "JYDD", "ROOM", "RoomName"),
                date = text(row, "YYRQ", "ReserveDate"),
                time = text(row, "YYSJ", "SKSJ", "TimeRange"),
                topic = text(row, "YYSM", "Topic"),
                status = text(row, "YYZTMC", "ZTMC", "Status"),
            )
        }

    /** Reservations used against the per-semester allowance. */
    fun quota(): CleQuota {
        val allowed = configs().firstOrNull()?.let { optionalInt(it, "ZDYYCS") } ?: 0
        return CleQuota(used = reservations().size, allowed = allowed)
    }

    private fun configs(): List<JSONObject> = rows(MODEL_CONFIG, "SFZZSY" to "1")

    private fun rows(model: String, vararg params: Pair<String, String>): List<JSONObject> {
        if (!bootstrapped) {
            throw ApiException("cle: the E-Hall session was not bootstrapped", signInRequired = true)
        }
        val url = buildString {
            append(BASE).append("/dxggyw/sys/yyzxyy/modules/fwyy/").append(model).append(".do")
            if (params.isNotEmpty()) {
                append('?').append(params.joinToString("&") { "${it.first}=${it.second}" })
            }
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CasLogin.UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", APP_INDEX)
            .build()
        val text = try {
            http.newCall(request).execute().use { response ->
                if (response.code == 403) {
                    // The adapter has not run (or the session lapsed): say so
                    // rather than reporting an empty list.
                    throw ApiException("cle: the E-Hall session was refused", signInRequired = true)
                }
                if (!response.isSuccessful) {
                    throw ApiException("cle: HTTP ${response.code}", httpStatus = response.code)
                }
                // Read once — a response body is a single-use stream.
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        return parseRows(model, text)
    }

    private fun parseRows(model: String, body: String): List<JSONObject> {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ApiException("cle: unexpected response", signInRequired = true)
        }
        val datas = json.optJSONObject("datas") ?: return emptyList()
        val model1 = datas.optJSONObject(model) ?: return emptyList()
        val rows = model1.optJSONArray("rows") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
    }

    private fun text(row: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = row.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    private fun optionalInt(row: JSONObject, key: String): Int? =
        row.optString(key).toIntOrNull()

    companion object {
        const val BASE = "https://ehall.sustech.edu.cn"
        const val APP_INDEX = "$BASE/dxggyw/sys/yyzxyy/*default/index.do"

        const val MODEL_CONFIG = "T_NKD_YYZX_FWPZ_QUERY"
        const val MODEL_RESERVATION = "T_NKD_YYZX_XSYY_QUERY"

        /** Models the Android side does not read yet, listed for the next pass. */
        const val MODEL_RESOURCE = "T_NKD_YYZX_FWZY_QUERY"
        const val MODEL_BUCKET = "T_NKD_YYZX_SKSJB_QUERY"
    }
}
