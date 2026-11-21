package edu.sustech.mobile.ui.bb

import android.content.Context
import androidx.core.text.HtmlCompat
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbAssessment

object BbAssessmentText {
    fun plain(html: String): String = HtmlCompat.fromHtml(html.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), ""), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
    fun summary(context: Context, item: BbAssessment): String = item.grade?.takeIf { item.gradeStatus in setOf("Graded", "Completed") }?.let {
        context.getString(R.string.bb_reported_grade, it, item.possible ?: "—")
    } ?: item.attempts.lastOrNull { it.status in setOf("Completed", "NeedsGrading", "NeedsGradingAgain") }?.let {
        if (it.pendingGrade) context.getString(R.string.bb_awaiting_grade) else it.score?.let { score -> context.getString(R.string.bb_attempt_score, score, item.possible ?: "—") }
    } ?: context.getString(R.string.bb_grade_unpublished)
    fun detail(context: Context, item: BbAssessment): String = buildList {
        add(summary(context, item))
        item.attempts.filter { it.status in setOf("Completed", "NeedsGrading", "NeedsGradingAgain") }.forEachIndexed { index, attempt ->
            add(context.getString(R.string.bb_attempt_label, index + 1))
            add(if (attempt.pendingGrade) context.getString(R.string.bb_awaiting_grade) else
                attempt.score?.let { context.getString(R.string.bb_attempt_score, it, item.possible ?: "—") }
                    ?: context.getString(R.string.bb_grade_unpublished))
            if (attempt.pendingGrade && attempt.score != null) add(context.getString(R.string.bb_attempt_score, attempt.score, item.possible ?: "—"))
            val feedback = plain(attempt.feedback)
            add(context.getString(R.string.bb_teacher_feedback) + "\n" + feedback.ifBlank { context.getString(R.string.bb_feedback_unpublished) })
        }
    }.joinToString("\n\n")
}
