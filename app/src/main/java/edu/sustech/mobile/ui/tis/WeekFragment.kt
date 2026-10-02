package edu.sustech.mobile.ui.tis

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.calendar.AcademicCalendar
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.tis.ClassEntry
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.localizedWeekday
import java.time.LocalDate

/**
 * This week — every meeting of the current teaching week, in order.
 *
 * The week number comes from TIS itself (`querydangqianzc`), so a term that has
 * not started yet reports honestly rather than guessing from the date.
 *
 * The rows come from the **whole-term** timetable rather than the single-week
 * endpoint: that one answers with the week's rows whether or not the course runs
 * in it (a "1-15单周" lab is listed in every week), while the whole-term payload
 * carries the `ZC` week bitmap every row is filtered by. Same for the clock
 * times, which come from TIS's own period grid.
 */
class WeekFragment : ListFragment<ClassEntry>(R.layout.fragment_tis_week) {

    private var headerText = ""

    override fun cachePrefix() = "tis.week"

    override fun rowLayout() = R.layout.item_tis_class

    override fun emptyText() = getString(R.string.tis_week_empty)

    override fun errorText(error: Throwable): String =
        if (error is ApiException && error.signInRequired) getString(R.string.tis_not_signed_in)
        else super.errorText(error)

    override suspend fun fetch(): List<ClassEntry> {
        val semester = App.tis.currentSemester()
        val week = App.tis.currentWeek()
        // The real teaching grid, so the times on screen are the times the rooms
        // use (the app used to carry an exam-hall table).
        runCatching { App.tis.loadSlotTimes(semester, week) }
        headerText = listOfNotNull(
            week?.let { getString(R.string.today_week, it) },
            semester.labelEn.ifEmpty { semester.label },
        ).joinToString(" · ")

        val current = week ?: 1
        val term = AcademicCalendar.termAt(App.context, LocalDate.now())
        return App.tis.semesterSchedule(semester)
            .filter { it.meets(current) }
            .distinctBy { "${it.name}|${it.weekday}|${it.periodFrom}|${it.periodTo}" }
            // A holiday flushes a whole day, so a row with no real meeting left
            // in this week is not something to plan around. Rows a holiday moved
            // stay, in the week they moved to. Without a calendar for the date
            // the pattern is all there is, and nothing is dropped.
            .filter { entry ->
                term == null || term.meetings(entry.effectiveWeeks, entry.weekday)
                    .any { term.weekOf(it) == current }
            }
            .sortedWith(compareBy({ it.weekday }, { it.periodFrom }))
    }

    override fun onLoaded(rows: List<ClassEntry>) {
        view?.findViewById<TextView>(R.id.week_header)?.text = headerText
    }

    override fun bindRow(view: View, item: ClassEntry, position: Int) {
        view.findViewById<TextView>(R.id.class_day).text = requireContext().localizedWeekday(item.weekday)
        view.findViewById<TextView>(R.id.class_time).text = item.timeText
        view.findViewById<TextView>(R.id.class_name).text = item.name
        view.findViewById<TextView>(R.id.class_meta).text = listOf(
            item.teacher,
            item.room,
            item.classGroup,
            getString(R.string.tis_weeks_range, item.weekRangeText),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}
