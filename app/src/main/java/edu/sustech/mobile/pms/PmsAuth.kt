package edu.sustech.mobile.pms

import android.util.Base64
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.AppConfig
import edu.sustech.mobile.sso.CasLogin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * PMS login.
 *
 * The 联创 site does not use SUSTech CAS for its API. It has its own flow:
 *
 *   1. POST /api/client/Auth/GetAuthToken  -> { szToken }
 *   2. GET  /api/client/Auth/PublicKey     -> { publicKey, nonceStr }
 *   3. RSA(PKCS#1 v1.5, base64) of `password + ";" + nonceStr`
 *   4. POST /api/client/Auth/Login         -> sets the OSESSIONID cookie
 *
 * The website is additionally CAS-fronted, which is why the app also offers
 * [edu.sustech.mobile.ui.CasLoginActivity]: sign in once in a WebView and the
 * PMS back end links the CAS identity to the print account. That path never
 * sees a password.
 */
object PmsAuth {

    /**
     * The page the print site's own client returns to after SSO (its mobile
     * index) — the value the site passes as `backurl` when it signs in.
     */
    const val MOBILE_PAGE = "/client/new/cprintMobile/index.html"

    /**
     * The sign-in the print site itself now uses.
     *
     * 🔴 The desktop print page this used to front with CAS
     * (`/client/new/cprintPc/`) is a **help page** since the site's 2026-09-23
     * build (measured 2026-10-28), so a CAS sign-in aimed at it lands on a page
     * that sets no session — every session-backed call then answers
     * `code 4 无效会话，未登录` while the public station list keeps working.
     * The current chain is the mobile client's:
     *
     *   1. `GET /api/client/Auth/SSoPage?backurl=…` → the authcenter URL
     *      (`<host>/authcenter/toLoginPage?redirectUrl=…/Auth/SSOLogin&targetPageUrl=…`)
     *   2. that URL redirects to CAS carrying a one-off service URL
     *   3. signing in there runs the relay chain, whose last hop sets the print
     *      session cookie
     *
     * Same shape as the library's discussion-room sign-in, so it goes through
     * [CasLogin.loginAtCasUrl] rather than a service URL this class builds.
     */
    fun ssoLogin(username: String, password: String, backurl: String): Boolean {
        val base = AppConfig.baseUrl
        val url = (base + "/api/client/Auth/SSoPage").toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("backurl", backurl)
            ?.build()
            ?: throw ApiException("Bad print server URL: $base")
        val body = send(
            Request.Builder()
                .url(url)
                .get()
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", base + MOBILE_PAGE)
                .build(),
        )
        val code = body.optInt("code", -1)
        if (code != 0) {
            throw ApiException("${body.optString("message", "SSoPage failed")} (code=$code)")
        }
        val entry = body.optString("result", "")
        if (!entry.contains("toLoginPage")) {
            throw ApiException("SSoPage returned no sign-in address: ${entry.take(120)}")
        }
        return CasLogin.loginAtCasUrl(entry, username, password)
    }

    /**
     * Posts the four-step password login and returns the account's display
     * name. Cookies land in [edu.sustech.mobile.core.CookieStore] because the
     * requests go through the shared OkHttp client.
     */
    fun login(username: String, password: String): String {
        val base = AppConfig.baseUrl

        val tokenBody = post(base, "/api/client/Auth/GetAuthToken", JSONObject())
        if (tokenBody.optInt("code", -1) != 0) {
            throw ApiException(tokenBody.optString("message", "GetAuthToken failed"))
        }
        val token = tokenBody.optString("szToken", "")
        if (token.isEmpty()) throw ApiException("GetAuthToken returned no szToken")

        val keyBody = get(base, "/api/client/Auth/PublicKey")
        if (keyBody.optInt("code", -1) != 0) {
            throw ApiException(keyBody.optString("message", "PublicKey failed"))
        }
        val keyResult = keyBody.optJSONObject("result") ?: JSONObject()
        val publicKey = keyResult.optString("publicKey", "")
        val nonce = keyResult.optString("nonceStr", "")
        if (publicKey.isEmpty()) throw ApiException("PublicKey returned no key")

        val loginBody = post(
            base,
            "/api/client/Auth/Login",
            JSONObject()
                .put("szLogonName", username)
                .put("szPassword", rsaEncrypt(publicKey, "$password;$nonce"))
                .put("szToken", token),
        )
        val code = loginBody.optInt("code", -1)
        if (code != 0) {
            // The login endpoint answered: the account itself was rejected.
            throw ApiException(
                "${loginBody.optString("message", "login failed")} (code=$code)",
                refused = true,
            )
        }
        AppConfig.lastUsername = username
        val result = loginBody.optJSONObject("result") ?: JSONObject()
        return result.optString("szTrueName", username)
    }

    // -- Transport ------------------------------------------------------------

    private fun post(base: String, path: String, payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(base + path)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", base + "/client/new/cprintPc/")
            .build()
        return send(request)
    }

    private fun get(base: String, path: String): JSONObject {
        val request = Request.Builder()
            .url(base + path)
            .get()
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Referer", base + "/client/new/cprintPc/")
            .build()
        return send(request)
    }

    private fun send(request: Request): JSONObject = App.http.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        // Out of the campus network the print host answers 403, or HTTP 200 with
        // its campus-only page — both are the location, never the account.
        if (response.code == 403 || text.startsWith("Access forbidden") ||
            PmsWire.html(text) == PmsWire.Html.ACCESS_NOTICE
        ) {
            throw ApiException("Off campus (HTTP ${response.code})", offCampus = true)
        }
        try {
            JSONObject(text)
        } catch (_: Exception) {
            throw ApiException("Non-JSON response: HTTP ${response.code}")
        }
    }

    // -- RSA ------------------------------------------------------------------

    /** JSEncrypt-compatible encryption: PKCS#1 v1.5 padding, base64 output. */
    fun rsaEncrypt(publicKey: String, plaintext: String): String {
        val der = decodeKey(publicKey)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Accepts a full PEM block or the raw base64 the server returns. */
    fun decodeKey(key: String): ByteArray {
        val body = key
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .filterNot { it.isWhitespace() }
        return Base64.decode(body, Base64.DEFAULT)
    }
}
