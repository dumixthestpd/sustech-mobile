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

/**
 * Language-centre tutoring (`ehall.sustech.edu.cn/dxggyw/sys/yyzxyy`, "CLE").
 *
 * 🔴 This is the one E-Hall app a CAS ticket alone cannot read: its data endpoints
 * answer **403** until the portal's own JavaScript adapter (EMAP) has run, and the
 * session it establishes does not survive being copied into another HTTP client —
 * the same request replayed from this app's OkHttp jar is still refused.
 *
 * So the queries are **issued by the page itself**, which is also what the
 * reference implementation does (it drives a browser and reads through the
 * browser context's request API). [JS_FETCH] is the snippet the screen evaluates;
 * this class only builds the path and parses the answer.
 *
 * Reads only: booking posts the reservation model's entire control set and
 * consumes one of three per-semester slots, so it stays on the official page.
 */
class CleApi(private val http: OkHttpClient) {

    @Volatile
    var bootstrapped: Boolean = false
        private set

    /** Marks the session usable once the screen's WebView has run the adapter. */
    fun markBootstrapped() {
        bootstrapped = true
    }

    /**
     * Makes sure an E-Hall session exists, so the bootstrap page loads without
     * showing a login form.
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

    /** A model query path for the page to fetch. */
    fun modelPath(model: String, vararg extra: Pair<String, String>): String {
        val query = listOf("pageSize" to "100", "pageNumber" to "1") + extra
        return "/dxggyw/sys/yyzxyy/modules/fwyy/$model.do?" +
            query.joinToString("&") { "${it.first}=${it.second}" }
    }

    fun reservations(body: String): List<CleReservation> =
        parseRows(body, MODEL_RESERVATION).map { row ->
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

    /**
     * How many reservations the semester allows (0 when the config is unreadable).
     *
     * 🔴 The field arrives as a JSON **number** (`3.0`), so read it numerically —
     * `optString(...).toIntOrNull()` yields null and the allowance silently reads 0.
     */
    fun allowedPerSemester(body: String): Int {
        parseRows(body, MODEL_CONFIG).forEach { row ->
            val value = row.optDouble("ZDYYCS", 0.0).toInt()
            if (value > 0) return value
        }
        return 0
    }

    /**
     * `{datas: {<model>: {rows: [...]}}}` — the EMAP envelope. A body that is not
     * JSON (an HTML error page) means the adapter's session was not accepted.
     */
    private fun parseRows(body: String, model: String): List<JSONObject> {
        if (body.startsWith(ERROR_PREFIX)) {
            throw ApiException("cle: ${body.removePrefix(ERROR_PREFIX).take(80)}")
        }
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ApiException("cle: the E-Hall session was not accepted", signInRequired = true)
        }
        val datas = json.optJSONObject("datas")
        val block = datas
            ?.let { it.optJSONObject(model) ?: it.optJSONObject("pageAction") }
            ?.optJSONArray("rows")
            ?: JSONArray()
        if (block.length() == 0) return emptyList()
        return (0 until block.length()).mapNotNull { block.optJSONObject(it) }
    }

    private fun text(row: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = row.optString(key)
            if (value.isNotBlank() && value != "null") return value
        }
        return ""
    }

    companion object {
        const val BASE = "https://ehall.sustech.edu.cn"
        const val APP_INDEX = "$BASE/dxggyw/sys/yyzxyy/*default/index.do"

        const val MODEL_CONFIG = "T_NKD_YYZX_FWPZ_QUERY"
        const val MODEL_RESERVATION = "T_NKD_YYZX_XSYY_QUERY"

        /** Models a later pass can read: the service/teacher list and time buckets. */
        const val MODEL_RESOURCE = "T_NKD_YYZX_FWZY_QUERY"
        const val MODEL_BUCKET = "T_NKD_YYZX_SKSJB_QUERY"

        /** The JS interface the page reports to; must match the screen's bridge. */
        const val BRIDGE = "CleBridge"

        const val ERROR_PREFIX = "__CLE_ERR__"

        /**
         * Runs inside the bootstrapped page: same cookies, same origin, same
         * session the adapter established. `__PATH__` is replaced with a
         * [modelPath] and `__ID__` with the asking request's id, so an answer can
         * never be handed to the wrong waiter.
         */
        val JS_FETCH = """
            (function () {
              fetch("__PATH__", {
                credentials: "same-origin",
                headers: {"x-requested-with": "XMLHttpRequest", "accept": "application/json, */*"}
              })
                .then(function (r) { return r.text(); })
                .then(function (t) { $BRIDGE.onResult("__ID__", t); })
                .catch(function (e) { $BRIDGE.onResult("__ID__", "$ERROR_PREFIX" + e); });
            })();
        """.trimIndent()
    }
}
