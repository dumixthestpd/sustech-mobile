package edu.sustech.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.view.Gravity
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
import edu.sustech.mobile.bb.BbApi
import edu.sustech.mobile.service.Services
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Today: the one screen worth opening between classes.
 *
 * Upcoming BB deadline, next class, service shortcuts, campus weather and air
 * quality, and the next exam. Academic records use the existing BB/TIS clients;
 * weather and air quality are public.
 *
 * The class line shows one meeting, not the day's timetable: the card answers
 * "what is next", and the parity of the weeks is spelled out, because a lab that
 * only runs on even weeks otherwise looks like it runs every week.
 */
class TodayFragment : Fragment(R.layout.fragment_today), Refreshable {
    private var activeDeadline: BbApi.Deadline? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.today_sign_in).setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }
        view.findViewById<SwipeRefreshLayout>(R.id.today_swipe).setOnRefreshListener {
            Cache.invalidate("tis.")
            refresh()
        }
        view.findViewById<View>(R.id.today_timetable).setOnClickListener {
            startActivity(ServiceActivity.intent(requireContext(), Services.courses))
        }
        view.findViewById<View>(R.id.today_deadline_open).setOnClickListener {
            val intent = ServiceActivity.intent(requireContext(), Services.blackboard)
            val deadline = activeDeadline
            if (deadline != null && deadline.courseId.isNotBlank()) {
                intent.putExtra("bb_course", deadline.courseId).putExtra("bb_content", deadline.contentId)
                    .putExtra("bb_course_name", deadline.courseName)
            } else intent.putExtra("bb_deadlines", true)
            startActivity(intent)
        }
        buildShortcuts(view.findViewById(R.id.today_shortcuts))
        load()
    }

    override fun refresh() { Cache.invalidate("bb.deadlines"); load() }

    private fun buildShortcuts(grid: GridLayout) {
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        listOf(Services.blackboard, Services.transit, Services.courses, Services.rooms,
            Services.printing, Services.exchange, Services.library, Services.booking).forEachIndexed { index, module ->
            val tile = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = dp(88)
                isClickable = true; isFocusable = true; contentDescription = getString(module.title)
                addView(ImageView(context).apply {
                    setImageResource(module.icon); setPadding(dp(10), dp(10), dp(10), dp(10))
                    setBackgroundResource(R.drawable.paper_input)
                    imageTintList = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.brand))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(context).apply {
                    text = getString(module.title); textSize = 11f; gravity = Gravity.CENTER
                    maxLines = 2; setTextColor(requireContext().getColor(R.color.ink)); setPadding(0, dp(5), 0, 0)
                }, LinearLayout.LayoutParams(-1, -2))
                setOnClickListener { startActivity(ServiceActivity.intent(requireContext(), module)) }
            }
            grid.addView(tile, GridLayout.LayoutParams(GridLayout.spec(index / 4), GridLayout.spec(index % 4, 1f)).apply { width = 0 })
        }
    }

    private fun loadDeadline(view: View) {
        activeDeadline = null
        val title = view.findViewById<TextView>(R.id.today_deadline_name)
        val clock = view.findViewById<TextView>(R.id.today_deadline_clock)
        val meta = view.findViewById<TextView>(R.id.today_deadline_meta)
        clock.setText(R.string.ui_recent_deadline)
        meta.text = ""
        title.setText(R.string.empty_loading)
        viewLifecycleOwner.runIo(block = { App.bb.deadlines() }, onOk = { rows ->
            val now = Instant.now()
            val next = rows.filter { it.at == null || !it.at.isBefore(now) }.minByOrNull { it.at ?: Instant.MAX }
            activeDeadline = next
            if (next == null) {
                clock.setText(R.string.ui_recent_deadline); title.setText(R.string.ui_no_future_deadlines); meta.text = ""
            } else {
                title.text = next.title
                meta.text = listOf(next.courseName, next.due).filter { it.isNotBlank() }.joinToString("\n")
                val seconds = next.at?.let { Duration.between(now, it).seconds.coerceAtLeast(0) }
                clock.text = when {
                    seconds == null -> getString(R.string.ui_recent_deadline)
                    seconds >= 172800 -> getString(R.string.ui_days_left, seconds / 86400)
                    seconds >= 3600 -> getString(R.string.ui_hours_left, seconds / 3600)
                    else -> getString(R.string.ui_minutes_left, (seconds + 59) / 60)
                }
            }
        }, onErr = { clock.setText(R.string.ui_recent_deadline); title.setText(R.string.ui_deadline_unavailable); meta.text = "" })
    }

    private fun load() {
        view?.let {
            it.findViewById<TextView>(R.id.today_date).text = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE", resources.configuration.locales[0]))
            loadDeadline(it)
        }
        val swipe = view?.findViewById<SwipeRefreshLayout>(R.id.today_swipe)
        val week = view?.findViewById<TextView>(R.id.today_week)
        val weekday = view?.findViewById<TextView>(R.id.today_weekday)
        val classes = view?.findViewById<TextView>(R.id.today_classes_list)
        val hint = view?.findViewById<TextView>(R.id.today_hint)
        val signIn = view?.findViewById<MaterialButton>(R.id.today_sign_in)
        val weather = view?.findViewById<TextView>(R.id.today_weather_value)
        val aqi = view?.findViewById<TextView>(R.id.today_aqi_value)
        val exam = view?.findViewById<TextView>(R.id.today_exam_value)

        val today = Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        val todayDate = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
        swipe?.isRefreshing = true
        weekday?.text = requireContext().localizedWeekday(isoWeekday(today))
        week?.setText(R.string.today_week_unknown)
        classes?.setText(R.string.empty_loading)
        hint?.visibility = View.GONE
        signIn?.visibility = View.GONE

        // Public data first — it works with no session at all.
        runIo(
            block = { App.weather.weather() },
            onOk = { w ->
                weather?.text = listOfNotNull(
                    w.tempC?.let { "$it°C" },
                    w.feelsLike?.let { getString(R.string.weather_feels_like, it) },
                    getString(if (w.rainExpected) R.string.weather_rain_expected else R.string.weather_no_rain),
                ).joinToString(" · ")
            },
            onErr = { weather?.setText(R.string.today_none) },
        )

        aqi?.setText(R.string.empty_loading)
        viewLifecycleOwner.runIo(block = { App.weather.airQuality() }, onOk = { air ->
            aqi?.text = air.aqi?.let { getString(R.string.air_model, it, air.observedAt.replace('T', ' ')) } ?: getString(R.string.air_unavailable)
        }, onErr = { aqi?.setText(R.string.air_unavailable) })

        runIo(
            block = {
                val semester = App.tis.currentSemester()
                val currentWeek = App.tis.currentWeek()
                runCatching { App.tis.loadSlotTimes(semester, currentWeek) }
                val entries = App.tis.semesterSchedule(semester)
                Timetable(semester, currentWeek, entries, AcademicCalendar.termAt(App.context, todayDate))
            },
            onOk = { timetable ->
                swipe?.isRefreshing = false
                // TIS owns the period times; ask once and the card shows the
                // clock hours the rooms actually use.
                week?.text = if (timetable.week == null) getString(R.string.today_week_unknown)
                else getString(R.string.today_week, timetable.week)
                weekday?.text = weekLine(timetable, today, todayDate)
                classes?.text = nextUpText(timetable, today, todayDate)
                hint?.visibility = View.GONE

                runIo(
                    block = { App.tis.exams() },
                    onOk = { exams ->
                        val next = exams.filter { record -> runCatching { !LocalDate.parse(record.date.take(10)).isBefore(todayDate) }.getOrDefault(false) }
                            .minWithOrNull(compareBy({ it.date }, { it.timeText }))
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
