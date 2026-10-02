package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import edu.sustech.mobile.R
import edu.sustech.mobile.calendar.AcademicCalendar
import edu.sustech.mobile.calendar.Term
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.tis.ClassEntry
import edu.sustech.mobile.tis.NextClass
import edu.sustech.mobile.tis.PeriodTimes
import edu.sustech.mobile.tis.Semester
import java.time.LocalDate
import java.util.Calendar

/**
 * Today: the one screen worth opening between classes.
 *
 * Week number, the single next class ("next up"), campus weather and air
 * quality, and the next exam. Only the weather is auth-free; everything else
 * needs TIS, and the screen says so instead of showing empty cards.
 *
 * The class line shows one meeting, not the day's timetable: the card answers
 * "what is next", and the parity of the weeks is spelled out, because a lab that
 * only runs on even weeks otherwise looks like it runs every week.
 */
class TodayFragment : Fragment(R.layout.fragment_today), Refreshable {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.today_sign_in).setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        view.findViewById<SwipeRefreshLayout>(R.id.today_swipe).setOnRefreshListener {
            Cache.invalidate("tis.")
            load()
        }
        load()
    }

    override fun refresh() = load()

    private fun load() {
        val swipe = view?.findViewById<SwipeRefreshLayout>(R.id.today_swipe)
        val week = view?.findViewById<TextView>(R.id.today_week)
        val weekday = view?.findViewById<TextView>(R.id.today_weekday)
        val classes = view?.findViewById<TextView>(R.id.today_classes_list)
        val hint = view?.findViewById<TextView>(R.id.today_hint)
        val signIn = view?.findViewById<MaterialButton>(R.id.today_sign_in)
        val weather = view?.findViewById<TextView>(R.id.today_weather_value)
        val aqi = view?.findViewById<TextView>(R.id.today_aqi_value)
        val exam = view?.findViewById<TextView>(R.id.today_exam_value)

        val today = Calendar.getInstance()
        val todayDate = LocalDate.now()
        swipe?.isRefreshing = true
        weekday?.text = requireContext().localizedWeekday(isoWeekday(today))
        week?.setText(R.string.today_week_unknown)
        classes?.setText(R.string.empty_loading)
        hint?.visibility = View.GONE
        signIn?.visibility = View.GONE

        // Public data first — it works with no session at all.
        runIo(
            block = { App.weather.weather() to runCatching { App.weather.airQuality() }.getOrNull() },
            onOk = { (w, air) ->
                weather?.text = listOfNotNull(
                    w.tempC?.let { "$it°C" },
                    w.feelsLike?.let { getString(R.string.weather_feels_like, it) },
                    getString(if (w.rainExpected) R.string.weather_rain_expected else R.string.weather_no_rain),
                ).joinToString(" · ")
                aqi?.text = air?.aqi?.let { "$it (${requireContext().localizedAirQuality(air.category)})" } ?: "—"
            },
            onErr = { weather?.setText(R.string.today_none) },
        )

        runIo(
            block = {
                val semester = App.tis.currentSemester()
                val currentWeek = App.tis.currentWeek()
                val entries = App.tis.semesterSchedule(semester)
                Timetable(semester, currentWeek, entries, AcademicCalendar.termAt(App.context, todayDate))
            },
            onOk = { timetable ->
                swipe?.isRefreshing = false
                // TIS owns the period times; ask once and the card shows the
                // clock hours the rooms actually use.
                runCatching { App.tis.loadSlotTimes(timetable.semester, timetable.week) }
                week?.text = if (timetable.week == null) getString(R.string.today_week_unknown)
                else getString(R.string.today_week, timetable.week)
                weekday?.text = weekLine(timetable, today, todayDate)
                classes?.text = nextUpText(timetable, today, todayDate)
                hint?.visibility = View.GONE

                runIo(
                    block = { App.tis.exams() },
                    onOk = { exams ->
                        val next = exams.firstOrNull()
                        exam?.text = next?.let { "${it.date} · ${it.course} · ${it.room}" }
                            ?: getString(R.string.today_none)
                    },
                    onErr = { exam?.setText(R.string.today_none) },
                )
            },
            onErr = {
                swipe?.isRefreshing = false
                week?.setText(R.string.today_week_unknown)
                classes?.setText(R.string.today_sign_in_hint)
                hint?.visibility = View.VISIBLE
                signIn?.visibility = View.VISIBLE
                exam?.setText(R.string.today_none)
            },
        )
    }

    /** One term's timetable, plus the calendar that says which dates are real. */
    private class Timetable(
        val semester: Semester,
        val week: Int?,
        val entries: List<ClassEntry>,
        val term: Term?,
    )

    /**
     * The muted line under the week: weekday, term, and the holiday when today
     * is one — the reason the "Next up" line skipped today.
     */
    private fun weekLine(timetable: Timetable, now: Calendar, today: LocalDate): String {
        val parts = mutableListOf(requireContext().localizedWeekday(isoWeekday(now)))
        val term = timetable.semester.labelEn.ifEmpty { timetable.semester.label }
        if (term.isNotEmpty()) parts.add(term)
        timetable.term?.holiday(today)?.let { parts.add(it.name) }
        return parts.joinToString(" · ")
    }

    /**
     * The next meeting, on the real calendar.
     *
     * Without a calendar for this date the pattern-only reading is used — what
     * the card did before the calendar existed. It is wrong on a holiday, but it
     * beats declaring no classes at all for a term the app has no data for.
     */
    private fun nextUpText(timetable: Timetable, now: Calendar, today: LocalDate): String {
        val currentPeriod = PeriodTimes.currentPeriod(now)
        val term = timetable.term
        val next = if (term != null) {
            NextClass.find(timetable.entries, term, today, currentPeriod)
        } else {
            NextClass.findInWeek(timetable.entries, timetable.week, today, currentPeriod)
        }
        if (next != null) {
            val prefix = if (next.date == today) "" else "${requireContext().localizedWeekday(next.date.dayOfWeek.value)} "
            return describe(next.entry, prefix)
        }
        // Nothing ahead in the pattern at all, versus nothing left of today's.
        return if (term == null && NextClass.hadClassToday(timetable.entries, timetable.week, today)) {
            getString(R.string.today_none_left)
        } else {
            getString(R.string.today_no_classes)
        }
    }

    private fun describe(entry: ClassEntry, prefix: String): String {
        val time = entry.timeText.ifEmpty { PeriodTimes.range(entry.periodFrom, entry.periodTo) }
        val head = "$prefix$time  ${entry.name}"
        val detail = listOfNotNull(
            entry.teacher.ifEmpty { null },
            entry.room.ifEmpty { null },
            entry.weekRangeText.ifEmpty { null }?.let { getString(R.string.tis_weeks_range, it) },
        )
        return if (detail.isEmpty()) head else head + "\n" + detail.joinToString(" · ")
    }

    /** TIS uses 1 = Monday … 7 = Sunday; Calendar uses 1 = Sunday. */
    private fun isoWeekday(calendar: Calendar): Int = calendar.get(Calendar.DAY_OF_WEEK).let {
        if (it == Calendar.SUNDAY) 7 else it - 1
    }
}
