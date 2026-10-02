package edu.sustech.mobile.ui.nces

import android.os.Bundle
import android.text.Html
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.nces.NcesCourse
import edu.sustech.mobile.nces.NcesReview
import edu.sustech.mobile.ui.ListFragment

/** Public NCES course search. Results open their read-only review list. */
class NcesFragment : ListFragment<NcesCourse>(R.layout.fragment_nces_courses) {

    @Volatile
    private var query = ""

    override fun rowLayout() = R.layout.item_nces_course

    override fun cachePrefix() = "nces.search."

    override fun emptyText(): String = if (query.isBlank()) {
        getString(R.string.nces_search_prompt)
    } else {
        getString(R.string.nces_no_courses)
    }

    override fun onReady(view: View) {
        val search = view.findViewById<EditText>(R.id.nces_search)
        query = search.text.toString()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                query = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        search.setOnEditorActionListener { _, action, _ ->
            val isSearch = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_NULL
            if (isSearch) load(force = true)
            isSearch
        }
    }

    override suspend fun fetch(): List<NcesCourse> = App.nces.searchCourses(query)

    override fun bindRow(view: View, item: NcesCourse, position: Int) {
        view.findViewById<TextView>(R.id.nces_course_name).text = item.name
        view.findViewById<TextView>(R.id.nces_course_meta).text = listOf(
            item.courseCode,
            item.teacherNames.ifBlank { getString(R.string.nces_teacher_unknown) },
        ).filter { it.isNotBlank() }.joinToString(" · ")
        view.findViewById<TextView>(R.id.nces_course_rating).text = if (item.reviewCount > 0) {
            getString(R.string.nces_course_rating, item.averageRating, item.reviewCount)
        } else {
            getString(R.string.nces_course_no_rating)
        }
        view.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.service_container, NcesReviewsFragment.newInstance(item))
                .addToBackStack("nces_reviews")
                .commit()
        }
    }

}

/** The first twenty public reviews for a course, displayed as plain text. */
class NcesReviewsFragment : ListFragment<NcesReview>(R.layout.fragment_nces_reviews) {

    private val course: NcesCourse
        get() = requireArguments().let { args ->
            NcesCourse(
                id = args.getLong(ARG_ID),
                name = args.getString(ARG_NAME).orEmpty(),
                courseCode = args.getString(ARG_CODE).orEmpty(),
                teacherNames = args.getString(ARG_TEACHERS).orEmpty(),
                reviewCount = args.getInt(ARG_REVIEW_COUNT),
                averageRating = args.getDouble(ARG_RATING),
            )
        }

    override fun rowLayout() = R.layout.item_nces_review

    override fun cachePrefix() = "nces.reviews.${course.id}."

    override fun emptyText() = getString(R.string.nces_no_reviews)

    override fun onReady(view: View) {
        val selected = course
        view.findViewById<TextView>(R.id.nces_detail_name).text = selected.name
        view.findViewById<TextView>(R.id.nces_detail_meta).text = listOf(
            selected.courseCode,
            selected.teacherNames.ifBlank { getString(R.string.nces_teacher_unknown) },
        ).filter { it.isNotBlank() }.joinToString(" · ")
        view.findViewById<TextView>(R.id.nces_detail_rating).text = if (selected.reviewCount > 0) {
            getString(R.string.nces_course_rating, selected.averageRating, selected.reviewCount)
        } else {
            getString(R.string.nces_course_no_rating)
        }
    }

    override suspend fun fetch(): List<NcesReview> = App.nces.reviews(course.id)

    override fun bindRow(view: View, item: NcesReview, position: Int) {
        view.findViewById<TextView>(R.id.nces_review_meta).text = if (item.term.isNotBlank()) {
            getString(R.string.nces_review_rating_term, item.rating, item.term)
        } else {
            getString(R.string.nces_review_rating, item.rating)
        }
        val plainText = Html.fromHtml(item.content, Html.FROM_HTML_MODE_COMPACT).toString().trim()
        view.findViewById<TextView>(R.id.nces_review_content).text =
            plainText.ifBlank { getString(R.string.nces_review_empty_content) }
    }

    companion object {
        private const val ARG_ID = "course_id"
        private const val ARG_NAME = "course_name"
        private const val ARG_CODE = "course_code"
        private const val ARG_TEACHERS = "course_teachers"
        private const val ARG_REVIEW_COUNT = "review_count"
        private const val ARG_RATING = "average_rating"

        fun newInstance(course: NcesCourse) = NcesReviewsFragment().apply {
            arguments = Bundle().apply {
                putLong(ARG_ID, course.id)
                putString(ARG_NAME, course.name)
                putString(ARG_CODE, course.courseCode)
                putString(ARG_TEACHERS, course.teacherNames)
                putInt(ARG_REVIEW_COUNT, course.reviewCount)
                putDouble(ARG_RATING, course.averageRating)
            }
        }
    }
}
