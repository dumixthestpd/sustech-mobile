package edu.sustech.mobile.sso

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Headless CAS sign-in — the same four steps `sustech_survival`'s
 * `CASAuthorizer` runs, ported to OkHttp.
 *
 *   1. GET the sign-in page for the service → scrape the `execution` token
 *   2. POST username / password / execution / _eventId=submit
 *   3. the response is a 302 to the service URL with `?ticket=ST-…`
 *   4. GET that ticket URL; the service sets its own session cookie
 *
 * This replaces the WebView sign-in entirely: TIS refuses a mobile browser's
 * sign-in, and a native client needs no browser at all. The desktop user agent
 * and `X-Requested-With` header match what the Python client sends, so the
 * service sees the same client shape.
 *
 * Cookies land in the shared [edu.sustech.mobile.core.CookieStore].
 */
object CasLogin {

    private const val CAS_PAGE = "https://cas.sustech.edu.cn/cas/login"

    /** Same desktop UA the Python authorizer uses. */
    const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Signs in and returns true when a ticket came back.
     *
     * @param serviceUrl the service's CAS entry point, e.g.
     *        `https://tis.sustech.edu.cn/cas`
     * @param xhr some services (TIS) only answer the CAS flow with the XHR header
     * @param submitValue value for the form's submit button; null to omit it
     */
    fun login(
        serviceUrl: String,
        sid: String,
        password: String,
        xhr: Boolean = true,
        submitValue: String? = null,
    ): Boolean {
        loginForTicket(serviceUrl, sid, password, xhr, submitValue)
        return true
    }

    /**
     * The same sign-in, returning the ticket URL rather than a bare success.
     *
     * Services that need the ticket *value* use this: venue booking exchanges it
     * for a bearer token (a `GetUserProfile` call carrying `St`), which "did the
     * sign-in work" cannot express.
     */
    fun loginForTicket(
        serviceUrl: String,
        sid: String,
        password: String,
        xhr: Boolean = true,
        submitValue: String? = null,
    ): String {
        if (sid.isEmpty() || password.isEmpty()) {
            throw ApiException("No credentials stored", signInRequired = true)
        }

        val page = CAS_PAGE + "?service=" + URLEncoder.encode(serviceUrl, "UTF-8")
        // When CAS still has a live SSO ticket-granting cookie, its GET skips
        // the login form and redirects straight to the service with a ticket.
        // Capture that redirect before the follow-redirect client consumes it.
        fetchImmediateTicket(page, xhr)?.let { ticketUrl ->
            exchangeTicket(ticketUrl, xhr)
            return ticketUrl
        }
        val execution = fetchExecution(page, xhr)
            ?: throw ApiException("CAS did not return an execution token")

        val form = FormBody.Builder()
            .add("username", sid)
            .add("password", password)
            .add("execution", execution)
            .add("_eventId", "submit")
            .apply { if (submitValue != null) add("submit", submitValue) }
            .build()

        // No redirect following here: the Location header IS the ticket.
        val post = Request.Builder()
            .url(page)
            .post(form)
            .header("User-Agent", UA)
            .apply { if (xhr) header("X-Requested-With", "XMLHttpRequest") }
            .build()

        val location = try {
            App.httpNoRedirect.newCall(post).execute().use { response ->
                when {
                    response.isRedirect -> response.header("Location")
                    response.code == 200 -> {
                        // Re-rendered sign-in page. Usually the account was
                        // rejected, but CAS also answers 200 for throttling and
                        // other refusals — read the page before blaming the
                        // password, and keep a snippet so the reason is visible.
                        val text = response.body?.string().orEmpty()
                        val plain = text.replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ").trim().take(160)
                        val throttled = listOf("频繁", "频率", "too many", "rate limit", "稍后")
                            .any { text.contains(it, ignoreCase = true) }
                        throw ApiException(
                            if (throttled) "CAS is rate-limiting sign-ins: $plain"
                            else "CAS refused the account: $plain",
                            refused = !throttled,
                        )
                    }
                    else -> throw ApiException("CAS answered HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        } ?: throw ApiException("CAS re-rendered the sign-in page", refused = true)

        // Redirected back to the sign-in page instead of the service: CAS
        // rejected the account. A transport problem throws above instead.
        if (!location.contains("ticket=")) {
            throw ApiException("CAS rejected the credentials", refused = true)
        }

        // Step 4 — walk the ticket; the service sets its session cookie here.
        val ticket = location.toHttpUrlOrNull()
            ?: throw ApiException("CAS returned a malformed ticket URL")
        try {
            App.httpFollow.newCall(
                Request.Builder()
                    .url(ticket)
                    .get()
                    .header("User-Agent", UA)
                    .apply { if (xhr) header("X-Requested-With", "XMLHttpRequest") }
                    .build(),
            ).execute().use { response ->
                if (response.code >= 400) throw ApiException("Ticket exchange failed: HTTP ${response.code}")
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        return ticket.toString()
    }

    private fun fetchExecution(page: String, xhr: Boolean): String? {
        val request = Request.Builder()
            .url(page)
            .get()
            .header("User-Agent", UA)
            .apply { if (xhr) header("X-Requested-With", "XMLHttpRequest") }
            .build()
        return try {
            App.httpFollow.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                Regex("name=\"execution\" value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }

    private fun fetchImmediateTicket(page: String, xhr: Boolean): String? {
        val request = Request.Builder()
            .url(page)
            .get()
            .header("User-Agent", UA)
            .apply { if (xhr) header("X-Requested-With", "XMLHttpRequest") }
            .build()
        return try {
            App.httpNoRedirect.newCall(request).execute().use { response ->
                val location = response.header("Location") ?: return@use null
                if (!response.isRedirect) return@use null
                val target = page.toHttpUrlOrNull()?.resolve(location)
                target?.takeIf { it.queryParameter("ticket") != null }?.toString()
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }

    private fun exchangeTicket(ticketUrl: String, xhr: Boolean) {
        val ticket = ticketUrl.toHttpUrlOrNull()
            ?: throw ApiException("CAS returned a malformed ticket URL")
        try {
            App.httpFollow.newCall(
                Request.Builder()
                    .url(ticket)
                    .get()
                    .header("User-Agent", UA)
                    .apply { if (xhr) header("X-Requested-With", "XMLHttpRequest") }
                    .build(),
            ).execute().use { response ->
                if (response.code >= 400) throw ApiException("Ticket exchange failed: HTTP ${response.code}")
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }
}
