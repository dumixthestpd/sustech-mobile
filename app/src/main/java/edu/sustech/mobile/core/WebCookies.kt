package edu.sustech.mobile.core

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import okhttp3.Cookie

/**
 * Cookie marshalling between the app's HTTP jar and an in-app WebView.
 *
 * Some services only answer once a page's own JavaScript has run, so the app has
 * to drive a WebView — which keeps its own cookie store. These two directions are
 * the only bridge:
 *
 *  - [install]: jar → WebView, so the page opens already signed in.
 *  - [capture]: WebView → jar, so plain HTTP endpoints inherit what the page set.
 *
 * Both live here rather than in each screen: the marshalling is fiddly (domains,
 * expiry, host-only cookies) and two copies would drift.
 */
object WebCookies {

    private const val CAS_HOST = "cas.sustech.edu.cn"

    /** Copies the jar's cookies for [url]'s host into [web], then reports done. */
    fun install(web: WebView, url: String, onInstalled: () -> Unit = {}) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        manager.setAcceptThirdPartyCookies(web, false)
        val host = Uri.parse(url).host.orEmpty()
        val cookies = (App.cookies.cookiesForHost(host) + App.cookies.cookiesForHost(CAS_HOST))
            .distinctBy { "${it.name}|${it.domain}|${it.path}" }
            .filter { it.expiresAt > System.currentTimeMillis() }
        if (cookies.isEmpty()) {
            onInstalled()
            return
        }
        var remaining = cookies.size
        cookies.forEach { cookie ->
            val origin = "https://${cookie.domain.trimStart('.')}/"
            manager.setCookie(origin, header(cookie)) {
                remaining -= 1
                if (remaining == 0) {
                    manager.flush()
                    onInstalled()
                }
            }
        }
    }

    /**
     * Copies the WebView's cookies for [host] into the jar and returns the header
     * that was imported (empty when the page set none).
     */
    fun capture(host: String, path: String = "/"): String {
        val header = CookieManager.getInstance().getCookie("https://$host/").orEmpty()
        if (header.isNotBlank()) App.cookies.replaceFromHeader(header, host, path)
        return header
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
