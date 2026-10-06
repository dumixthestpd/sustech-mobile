package edu.sustech.mobile.core

import android.webkit.CookieManager
import android.webkit.WebView
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Cookie marshalling between the app's HTTP jar and an in-app WebView.
 *
 * Some services only answer once a page's own JavaScript has run, so the app has
 * to drive a WebView — which keeps its own cookie store. [install] is the bridge
 * from the jar into the page, so the page opens already signed in.
 *
 * It lives here rather than in each screen: the marshalling is fiddly (domains,
 * paths, expiry, host-only cookies) and two copies would drift.
 */
object WebCookies {

    private const val CAS_HOST = "cas.sustech.edu.cn"

    /**
     * Copies the jar's cookies that apply to [url] into [web], then reports done.
     *
     * 🔴 Match against the **full URL**, not just its host: a session cookie scoped
     * to a path (`/dxggyw/…`) does not match the host root, so matching by host
     * quietly drops exactly the cookie that matters and the page loads signed out.
     *
     * Sets them without waiting on `setCookie`'s callback: on some builds that
     * callback never fires when the WebView is off-screen, and a bootstrap that
     * waits for it silently never starts. The manager applies cookies
     * synchronously, so ordering is still guaranteed.
     */
    fun install(web: WebView, url: String, onInstalled: () -> Unit = {}) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(web, false)
        val target = url.toHttpUrlOrNull()
        val cookies = buildList {
            if (target != null) addAll(App.cookies.cookiesForUrl(target))
            addAll(App.cookies.cookiesForHost(CAS_HOST))
        }
            .distinctBy { "${it.name}|${it.domain}|${it.path}" }
            .filter { it.expiresAt > System.currentTimeMillis() }
        cookies.forEach { cookie ->
            // The origin has to carry the cookie's own path, or a path-scoped
            // cookie is stored under the wrong scope (or refused).
            val origin = "https://${cookie.domain.trimStart('.')}${cookie.path}"
            manager.setCookie(origin, header(cookie))
        }
        manager.flush()
        onInstalled()
    }

    private fun header(cookie: Cookie): String = buildString {
        append(cookie.name).append('=').append(cookie.value)
        append("; Path=").append(cookie.path)
        if (!cookie.hostOnly) append("; Domain=.").append(cookie.domain.trimStart('.'))
        if (cookie.secure) append("; Secure")
        if (cookie.httpOnly) append("; HttpOnly")
        if (cookie.expiresAt < Long.MAX_VALUE) {
            val maxAge = ((cookie.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            append("; Max-Age=").append(maxAge)
        }
    }
}
