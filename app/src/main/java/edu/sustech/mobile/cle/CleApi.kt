package edu.sustech.mobile.cle

import edu.sustech.mobile.core.ApiException
import org.json.JSONArray
import org.json.JSONObject

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
 * 🔴 This is the one E-Hall app a CAS ticket alone cannot read. Measured
 * 2026-10-06, signed out:
 *   - `index.do` answers **200 with a shell**, so it cannot tell "signed in" from
 *     "signed out"; in the WebView it then loops (`net::ERR_TOO_MANY_REDIRECTS`).
 *   - `/jsonp/userInfo.json` also answers 200.
 *   - The model endpoint answers **302**, and with redirects followed that becomes
 *     OkHttp's `"Too many follow-up requests: 21"` — a network error that hides a
 *     sign-in problem.
 *   - Copying the session into this app's own HTTP client does not work either:
 *     the same query replayed from the jar is still refused even with the page's
 *     cookies in it.
 *
 * So the screen drives a WebView that behaves like a browser — it signs in on the
 * page when CAS asks, and issues the queries from inside the page — and this class
 * only builds the paths and reads the answers.
 *
 * The sign-in the page has to go through is E-Hall's `amp-auth-adapter`
 * (`/amp-auth-adapter/login?service=…`): it is what mints the session, handing CAS
 * a `loginSuccess?sessionToken=…` callback of its own making, which is why a plain
 * CAS sign-in for the app URL loops instead of working.
 *
 * Reads only: booking posts the reservation model's entire control set and
 * consumes one of three per-semester slots, so it stays on the official page.
 */
class CleApi {

    @Volatile
    var bootstrapped: Boolean = false
        private set

    /** Marks the session usable once the screen's WebView is on the app page. */
    fun markBootstrapped() {
        bootstrapped = true
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
                teacher = text(row, "JSXM", "JSRXM", "TeacherName"),
                serviceType = text(row, "FWZYMXMC", "FWZYMC", "ServiceName"),
                room = text(row, "JYDD", "ROOM", "RoomName"),
                date = text(row, "YYRQ", "ReserveDate"),
                time = text(row, "YYSJ", "SKSJ", "TimeRange"),
                topic = text(row, "YYSM", "Topic"),
                status = text(row, "YYZT_DISPLAY", "YYZTMC", "ZTMC", "Status"),
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
     * JSON (an HTML error page) means the page's session was not accepted.
     */
    private fun parseRows(body: String, model: String): List<JSONObject> {
        if (body.startsWith(ERROR_PREFIX)) {
            throw ApiException("cle: ${body.removePrefix(ERROR_PREFIX).take(90)}")
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

        const val EHALL_HOST = "ehall.sustech.edu.cn"
        const val CAS_HOST = "cas.sustech.edu.cn"

        const val MODEL_CONFIG = "T_NKD_YYZX_FWPZ_QUERY"
        const val MODEL_RESERVATION = "T_NKD_YYZX_XSYY_QUERY"

        /** Models a later pass can read: the service/teacher list and time buckets. */
        const val MODEL_RESOURCE = "T_NKD_YYZX_FWZY_QUERY"
        const val MODEL_BUCKET = "T_NKD_YYZX_SKSJB_QUERY"

        /** The JS interface the page reports to; must match the screen's bridge. */
        const val BRIDGE = "CleBridge"

        const val ERROR_PREFIX = "__CLE_ERR__"

        /**
         * Runs inside the page: same cookies, same origin, same session, so it
         * needs no credentials of its own. `__PATH__` is replaced with a
         * [modelPath] and `__ID__` with the asking request's id, so an answer can
         * never be handed to the wrong waiter.
         */
        val JS_FETCH = """
            (function () {
              var id = "__ID__";
              fetch("__PATH__", {
                credentials: "same-origin",
                headers: {"x-requested-with": "XMLHttpRequest", "accept": "application/json, */*"}
              })
                .then(function (r) {
                  return r.text().then(function (t) { return {s: r.status, u: r.url, b: t}; });
                })
                .then(function (o) {
                  if (o.b.charAt(0) === "{") { $BRIDGE.onResult(id, o.b); }
                  else { $BRIDGE.onResult(id, "$ERROR_PREFIX" + o.s + " " + o.u); }
                })
                .catch(function (e) { $BRIDGE.onResult(id, "$ERROR_PREFIX" + e); });
            })();
        """.trimIndent()

        /**
         * Types the stored account into CAS's own form and submits it.
         *
         * 🔴 Click the submit control rather than calling `form.submit()`: CAS's
         * theme runs its own submit handling, and a bare `form.submit()` is
         * re-rendered as the login page again — which shows up as the page
         * reloading on the same CAS URL in a loop.
         *
         * `__SID__` / `__PASSWORD__` are replaced with JSON-quoted values.
         */
        val JS_SIGN_IN = """
            (function () {
              var user = document.querySelector("input[name=username]");
              var pass = document.querySelector("input[name=password]");
              if (!user || !pass) { $BRIDGE.onSignIn("no-form"); return; }
              user.value = __SID__;
              pass.value = __PASSWORD__;
              user.dispatchEvent(new Event("input", {bubbles: true}));
              pass.dispatchEvent(new Event("input", {bubbles: true}));
              var button = document.querySelector(
                "input[name=submit], input[type=submit], button[type=submit], #login"
              );
              if (button) { button.click(); $BRIDGE.onSignIn("clicked"); }
              else { document.querySelector("form").submit(); $BRIDGE.onSignIn("submitted"); }
            })();
        """.trimIndent()

    }
}
