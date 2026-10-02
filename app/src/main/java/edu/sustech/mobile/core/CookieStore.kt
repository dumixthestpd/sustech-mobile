package edu.sustech.mobile.core

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cookie jar backed by SharedPreferences so the PMS session survives an app
 * restart the same way it survives a browser tab close.
 *
 * Only cookies are stored — never the password, and never the RSA material.
 */
class CookieStore(context: Context) : CookieJar {

    private val prefs = context.applicationContext
        .getSharedPreferences("sustech_mobile_session", Context.MODE_PRIVATE)

    private val cookies = LinkedHashMap<String, Cookie>()

    init {
        load()
    }

    private fun key(c: Cookie): String = "${c.name}|${c.domain}|${c.path}|${c.hostOnly}"

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val builder = Cookie.Builder()
                    .name(o.getString("name"))
                    .value(o.getString("value"))
                    .path(o.optString("path", "/"))
                val domain = o.optString("domain", "")
                if (domain.isEmpty()) continue
                // Older app versions did not persist attributes. Restore those
                // conservatively as host-only, HTTPS-only session cookies.
                if (o.optBoolean("hostOnly", true)) builder.hostOnlyDomain(domain.trimStart('.'))
                else builder.domain(domain.trimStart('.'))
                val expiresAt = o.optLong("expiresAt", Long.MAX_VALUE)
                if (expiresAt < Long.MAX_VALUE) {
                    if (expiresAt <= System.currentTimeMillis()) continue
                    builder.expiresAt(expiresAt)
                }
                if (o.optBoolean("secure", true)) builder.secure()
                if (o.optBoolean("httpOnly", true)) builder.httpOnly()
                val cookie = builder.build()
                cookies[key(cookie)] = cookie
            }
        } catch (_: Exception) {
            cookies.clear()
        }
    }

    private fun save() {
        val array = JSONArray()
        for (c in cookies.values) {
            array.put(JSONObject().apply {
                put("name", c.name)
                put("value", c.value)
                put("domain", c.domain)
                put("path", c.path)
                put("hostOnly", c.hostOnly)
                put("secure", c.secure)
                put("httpOnly", c.httpOnly)
                put("expiresAt", c.expiresAt)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookieList: List<Cookie>) {
        var changed = false
        for (c in cookieList) {
            cookies[key(c)] = c
            changed = true
        }
        if (changed) save()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val expired = cookies.values.filter { it.expiresAt < now }
        if (expired.isNotEmpty()) {
            for (c in expired) cookies.remove(key(c))
            save()
        }
        return cookies.values.filter { it.matches(url) }
    }

    /** Replaces the stored cookies for [host] with a `name=value; …` header. */
    @Synchronized
    fun replaceFromHeader(header: String, host: String, path: String = "/") {
        cookies.entries.removeAll { it.value.domain.endsWith(host) }
        for (pair in header.split(";")) {
            val trimmed = pair.trim()
            if (trimmed.isEmpty()) continue
            val idx = trimmed.indexOf('=')
            if (idx <= 0) continue
            val builder = Cookie.Builder()
                .name(trimmed.substring(0, idx).trim())
                .value(trimmed.substring(idx + 1).trim())
                .domain(host)
                .path(path)
            cookies["${trimmed.substring(0, idx).trim()}|$host|$path"] = builder.build()
        }
        save()
    }

    /** The `Cookie:` header value for [host], or an empty string. */
    @Synchronized
    fun headerFor(host: String): String =
        cookies.values.filter { it.domain.endsWith(host) }
            .joinToString("; ") { "${it.name}=${it.value}" }

    /** Session cookies that can be installed into an in-app WebView for [host]. */
    @Synchronized
    fun cookiesForHost(host: String): List<Cookie> {
        val url = HttpUrl.Builder().scheme("https").host(host).build()
        val expired = cookies.values.filter { it.expiresAt < System.currentTimeMillis() }
        if (expired.isNotEmpty()) {
            expired.forEach { cookies.remove(key(it)) }
            save()
        }
        return cookies.values.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cookies.clear()
        prefs.edit().remove(KEY).apply()
    }

    @Synchronized
    fun isEmpty(): Boolean = cookies.isEmpty()

    private companion object {
        const val KEY = "cookies"
    }
}
