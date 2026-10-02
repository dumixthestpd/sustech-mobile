package edu.sustech.mobile.nces

import edu.sustech.mobile.core.ApiException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Public, read-only NCES endpoints used by the course browser. */
class NcesApi(private val http: OkHttpClient) {

    fun searchCourses(query: String): List<NcesCourse> {
        if (query.isBlank()) return emptyList()
        val response = get(
            "/search",
            mapOf("q" to query.trim(), "type" to "course", "page" to "1", "per_page" to "20"),
        )
        val items = response.optJSONObject("courses")?.optJSONArray("items") ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let(NcesCourse::from)
        }
    }

    fun reviews(courseId: Long): List<NcesReview> {
        val response = get(
            "/course/$courseId/reviews",
            mapOf("page" to "1", "per_page" to "20", "sort_by" to "pubtime_desc"),
        )
        val items = response.optJSONArray("items")
            ?: response.optJSONObject("reviews")?.optJSONArray("items")
            ?: JSONArray()
        return (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let(NcesReview::from)
        }.filterNot { it.onlyVisibleToStudent || it.hidden || it.blocked }
    }

    private fun get(path: String, parameters: Map<String, String>): JSONObject {
        val base = (BASE_URL + path).toHttpUrlOrNull()
            ?: throw ApiException("Invalid NCES endpoint")
        val url = base.newBuilder().apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException("NCES request failed", httpStatus = response.code)
            }
            val body = response.body?.string().orEmpty()
            return runCatching { JSONObject(body) }
                .getOrElse { throw ApiException("NCES returned an invalid response") }
        }
    }

    private companion object {
        const val BASE_URL = "https://ncesnext.com/api/v1"
    }
}

data class NcesCourse(
    val id: Long,
    val name: String,
    val courseCode: String,
    val teacherNames: String,
    val reviewCount: Int,
    val averageRating: Double,
) {
    companion object {
        fun from(raw: JSONObject) = NcesCourse(
            id = raw.optLong("id"),
            name = raw.optString("name"),
            courseCode = raw.optString("course_code"),
            teacherNames = raw.optString("teacher_names"),
            reviewCount = raw.optInt("review_count"),
            averageRating = raw.optDouble("rate_average", 0.0),
        )
    }
}

data class NcesReview(
    val term: String,
    val rating: Double,
    val content: String,
    val onlyVisibleToStudent: Boolean,
    val hidden: Boolean,
    val blocked: Boolean,
) {
    companion object {
        fun from(raw: JSONObject) = NcesReview(
            term = raw.optString("term_display").ifBlank { raw.optString("term") },
            rating = raw.optDouble("rate", 0.0),
            content = raw.optString("content"),
            onlyVisibleToStudent = raw.optBoolean("only_visible_to_student"),
            hidden = raw.optBoolean("is_hidden"),
            blocked = raw.optBoolean("is_blocked"),
        )
    }
}
