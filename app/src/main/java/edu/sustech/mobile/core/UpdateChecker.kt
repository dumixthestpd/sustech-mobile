package edu.sustech.mobile.core

import edu.sustech.mobile.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Update detection against the project's GitHub Releases.
 *
 * The app ships outside any store, so "a new version exists" is learned from
 * the Releases API: GET /releases/latest, compare `tagName` with the running
 * [BuildConfig.VERSION_NAME], and hand back the release's notes URL plus the
 * first `.apk` asset so the user can grab it in a browser.
 *
 * Deliberately detection + hand-off only: an in-app APK downloader would need
 * INSTALL_PACKAGES prompting, storage permissions and hash pinning to be
 * honest about safety, and the browser already does the job.
 *
 * Version comparison is numeric-per-dot-segment ("0.3.20" > "0.3.11"), so a
 * suffix like `-shortcuts` never breaks the parse — it is ignored on both
 * sides, meaning a re-release with only a suffix bump is not detected. That
 * matches how the versions are actually used (the suffix names the feature
 * branch, the numbers carry the order).
 */
object UpdateChecker {

    /** Where the release metadata comes from; the public API needs no auth. */
    private const val LATEST_URL =
        "https://api.github.com/repos/dumixthestpd/sustech-mobile/releases/latest"

    /** The page a "get it" button opens. */
    const val RELEASES_PAGE = "https://github.com/dumixthestpd/sustech-mobile/releases/latest"

    data class Update(
        /** The release tag, e.g. `v0.3.21`. */
        val tag: String,
        /** Human release name, e.g. `v0.3.21 — loans`. */
        val name: String,
        /** Release notes URL to open in a browser. */
        val pageUrl: String,
        /** Direct APK download URL, when the release ships one. */
        val apkUrl: String?,
        /** APK size in bytes, when known. */
        val apkSize: Long?,
    )

    /**
     * The latest release, or null when the running build is already current —
     * the two answers a caller cares about, with no third state to misread.
     * Network and parse failures throw [ApiException]; callers show friendly
     * text and move on. Update checks must never block anything.
     */
    fun check(http: OkHttpClient): Update? {
        val request = Request.Builder()
            .url(LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "sustech-mobile")
            .build()
        val body = try {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return null // no release published yet
                if (response.code >= 400) {
                    throw ApiException("releases answered HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
        val release = try {
            JSONObject(body)
        } catch (_: Exception) {
            throw ApiException("releases returned a non-JSON reply")
        }
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name").removePrefix("v")
        if (tag.isEmpty()) return null
        if (compareVersions(BuildConfig.VERSION_NAME, tag) >= 0) return null
        val assets = release.optJSONArray("assets") ?: JSONArray_EMPTY
        var apkUrl: String? = null
        var apkSize: Long? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url").ifEmpty { null }
                apkSize = if (asset.has("size")) asset.optLong("size") else null
                break
            }
        }
        return Update(
            tag = "v$tag",
            name = release.optString("name").ifEmpty { "v$tag" },
            pageUrl = release.optString("html_url").ifEmpty { RELEASES_PAGE },
            apkUrl = apkUrl,
            apkSize = apkSize,
        )
    }

    /**
     * Dot-separated numeric comparison; non-numeric segments (the `-suffix`
     * feature tag) are ignored on both sides. Returns >0 when [running] is
     * newer, 0 when equal, <0 when [latest] is newer.
     */
    internal fun compareVersions(running: String, latest: String): Int {
        val left = running.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
        val right = latest.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0L }
            val b = right.getOrElse(index) { 0L }
            if (a != b) return if (a < b) -1 else 1
        }
        return 0
    }

    private val JSONArray_EMPTY get() = org.json.JSONArray()
}
