package edu.sustech.mobile.bb

import edu.sustech.mobile.core.ApiException
import org.json.JSONObject
import java.net.URI

enum class SubmissionState { UNSUBMITTED, DRAFT, SUBMITTED, UNKNOWN }
data class BbAttempt(val id: String, val status: String, val score: String?, val feedback: String, val created: String) {
    val pendingGrade get() = status == "NeedsGrading" || status == "NeedsGradingAgain"
}
data class BbAssessment(val courseId: String, val contentId: String, val columnId: String, val title: String,
    val state: SubmissionState, val attempts: List<BbAttempt>, val possible: String?, val grade: String?, val gradeStatus: String = "")

object BbSubmissionRules {
    fun state(statuses: List<String>, ownershipKnown: Boolean = true): SubmissionState = when {
        !ownershipKnown -> SubmissionState.UNKNOWN
        statuses.any { it in setOf("Completed", "NeedsGrading", "NeedsGradingAgain") } -> SubmissionState.SUBMITTED
        statuses.isEmpty() || statuses.all { it == "NotAttempted" } -> SubmissionState.UNSUBMITTED
        statuses.all { it in setOf("InProgress", "NotAttempted") } -> SubmissionState.DRAFT
        else -> SubmissionState.UNKNOWN
    }
}

/** Complete collections, validated owners and attempt states mirror Survival's bb.assessments. */
class BbAssessmentReader(private val get: (String) -> JSONObject) {
    private val api = "/learn/api/public/v1"
    fun pages(initial: String): List<JSONObject> {
        var path = initial
        val seen = hashSetOf<String>(); val rows = mutableListOf<JSONObject>()
        repeat(100) {
            if (!seen.add(path)) throw ApiException("Blackboard pagination looped")
            val page = get(path)
            val data = page.optJSONArray("results") ?: throw ApiException("Blackboard collection is unavailable")
            for (i in 0 until data.length()) rows += data.optJSONObject(i) ?: throw ApiException("Invalid Blackboard collection")
            val next = BbWire.nextPage(page) ?: return rows
            val target = URI("https://bb.sustech.edu.cn$path").resolve(next)
            if (target.scheme != "https" || target.host != "bb.sustech.edu.cn") throw ApiException("Blackboard pagination left its host")
            path = target.rawPath + (target.rawQuery?.let { q -> "?$q" } ?: "")
        }
        throw ApiException("Blackboard collection exceeded its page limit")
    }
    fun columns(course: String) = pages("$api/courses/${BbWire.bracket(course)}/gradebook/columns?limit=200")
    fun assessment(course: String, column: JSONObject, me: String, cells: List<JSONObject>?): BbAssessment {
        val courseId = BbWire.bracket(course); val columnId = column.optString("id")
        if (me.isBlank() || columnId.isBlank()) throw ApiException("Blackboard assessment identity is unavailable")
        val all = pages("$api/courses/$courseId/gradebook/columns/$columnId/attempts?limit=200")
        val own = all.filter { it.optString("userId") == me && !it.optBoolean("exempt") }
        val attempts = own.map { a ->
            val score = a.opt("score"); val display = a.optJSONObject("displayGrade")
            val value = if (score is JSONObject) number(score.opt("raw")) ?: number(score.opt("display")) else number(score)
            BbAttempt(a.optString("id"), a.optString("status"), value ?: number(display?.opt("score"))
                ?: number(display?.opt("text")) ?: number(a.opt("text")), if (a.isNull("feedback")) "" else a.optString("feedback"), a.optString("created"))
        }.sortedWith(compareBy({ it.created }, { it.id }))
        val cell = cells?.firstOrNull { it.optString("userId") == me && it.optString("columnId") == columnId }
        val rawState = BbSubmissionRules.state(attempts.map { it.status }, all.all { it.optString("userId").isNotBlank() })
        val state = if (cell?.optBoolean("exempt") == true || (rawState != SubmissionState.SUBMITTED && cells == null) ||
            (rawState == SubmissionState.UNSUBMITTED && cell?.optString("status") in setOf("Graded", "NeedsGrading", "NeedsGradingAgain"))) SubmissionState.UNKNOWN else rawState
        return BbAssessment(courseId, column.optString("contentId"), columnId, column.optString("name"), state, attempts,
            number(column.optJSONObject("score")?.opt("possible")), number(cell?.opt("score")) ?: number(cell?.opt("text")), cell?.optString("status").orEmpty())
    }
    private fun number(value: Any?): String? = value?.takeUnless { it == JSONObject.NULL }?.let {
        if (it is Number) java.math.BigDecimal(it.toString()).stripTrailingZeros().toPlainString() else it.toString().takeIf { text -> text.isNotBlank() }
    }
}
