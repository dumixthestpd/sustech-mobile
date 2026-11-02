package edu.sustech.mobile.ui.bb

import android.content.Intent
import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbApi
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.LoginActivity
import edu.sustech.mobile.ui.ServicePage
import edu.sustech.mobile.ui.TabbedServiceFragment

/**
 * Blackboard: the course list and every dated assignment, from the same
 * public REST API the Python client reads.
 *
 * Two pages, no deeper navigation — the phone surface of BB is "what am I
 * enrolled in" and "what is due", everything else stays on the website.
 */
class BbFragment : TabbedServiceFragment(R.layout.fragment_tabs) {

    override fun pages(): List<ServicePage> = listOf(
        ServicePage(R.string.bb_courses) { BbCoursesFragment() },
        ServicePage(R.string.bb_deadlines) { BbDeadlinesFragment() },
    )

    override fun onServiceViewReady(view: View) = checkSession()

    private fun checkSession() {
        banner(null, null)
        runIo(
            block = { runCatching { App.bb.isSignedIn() }.getOrDefault(false) ||
                runCatching { edu.sustech.mobile.sso.Session.reloginBb() }.getOrDefault(false) },
            onOk = { ok ->
                if (!ok) {
                    banner(getString(R.string.bb_not_signed_in)) {
                        startActivity(Intent(requireContext(), LoginActivity::class.java))
                    }
                }
            },
        )
    }
}

/** Courses — one row per enrolled course, latest access first. */
class BbCoursesFragment : ListFragment<BbApi.Course>(R.layout.fragment_list) {

    override fun cachePrefix() = "bb.courses"

    override fun rowLayout() = R.layout.item_tis_course

    override fun emptyText() = getString(R.string.bb_courses_empty)

    override fun errorText(error: Throwable): String =
        if (error is ApiException && error.signInRequired)
            context?.getString(R.string.bb_not_signed_in) ?: error.message.orEmpty()
        else super.errorText(error)

    override suspend fun fetch(): List<BbApi.Course> = App.bb.courses()

    override fun bindRow(view: View, item: BbApi.Course, position: Int) {
        view.findViewById<TextView>(R.id.course_code).text = item.id
        view.findViewById<TextView>(R.id.course_name).text = item.name
        view.findViewById<TextView>(R.id.course_meta).text = listOf(
            item.term,
            item.lastAccessed,
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        view.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(
                    R.id.service_container,
                    BbContentsFragment.newInstance(item.id, item.name),
                )
                .addToBackStack("bb_course_${item.id}")
                .commit()
        }
    }
}

/** Deadlines — every assignment column with a due date, soonest first. */
class BbDeadlinesFragment : ListFragment<BbApi.Deadline>(R.layout.fragment_list) {

    override fun cachePrefix() = "bb.deadlines"

    override fun rowLayout() = R.layout.item_tis_course

    override fun emptyText() = getString(R.string.bb_deadlines_empty)

    override fun errorText(error: Throwable): String =
        if (error is ApiException && error.signInRequired)
            context?.getString(R.string.bb_not_signed_in) ?: error.message.orEmpty()
        else super.errorText(error)

    override suspend fun fetch(): List<BbApi.Deadline> = App.bb.deadlines()

    override fun bindRow(view: View, item: BbApi.Deadline, position: Int) {
        view.findViewById<TextView>(R.id.course_code).text = item.due.take(16).replace("T", " ")
        view.findViewById<TextView>(R.id.course_name).text = item.title
        view.findViewById<TextView>(R.id.course_meta).text = item.courseName
    }
}
