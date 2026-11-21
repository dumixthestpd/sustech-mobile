package edu.sustech.mobile.ui.tis
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import edu.sustech.mobile.R
import edu.sustech.mobile.calendar.AcademicCalendar
import edu.sustech.mobile.core.*
import edu.sustech.mobile.tis.*
import edu.sustech.mobile.ui.Refreshable
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import com.google.android.material.button.MaterialButton

class WeekFragment : Fragment(), Refreshable {
    private val zone = ZoneId.of("Asia/Shanghai")
    private var selected = LocalDate.now(zone)
    private var dayOnly = false
    private var entries = emptyList<ClassEntry>()
    private var exams = emptyList<ExamRecord>()
    private var week: Int? = null
    private var examsFailed = false
    private lateinit var title: TextView
    private lateinit var body: LinearLayout
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        selected = state?.getString("date")?.let { LocalDate.parse(it) } ?: selected
        dayOnly = state?.getBoolean("day") ?: arguments?.getBoolean("day") ?: dayOnly
        val root = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val controls = LinearLayout(requireContext())
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        fun button(label: String, action: () -> Unit) {
            controls.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label; textSize = 12f; isAllCaps = false; cornerRadius = dp(12)
                minimumWidth = 0; setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        button("‹") { selected = selected.minusDays(if (dayOnly) 1 else 7); render() }
        button(getString(R.string.agenda_today)) { selected = LocalDate.now(zone); dayOnly = true; render() }
        button(getString(R.string.agenda_week)) { dayOnly = false; render() }
        button("›") { selected = selected.plusDays(if (dayOnly) 1 else 7); render() }
        root.addView(controls)
        title = TextView(requireContext()).apply { setPadding(16, 8, 16, 8) }; root.addView(title)
        body = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        val scroll = object : ScrollView(requireContext()) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                // When embedded on Today, the outer home scroll must not take
                // vertical drags intended for the afternoon/evening timetable.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    parent?.requestDisallowInterceptTouchEvent(false)
                return super.dispatchTouchEvent(event)
            }
        }.apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }
    override fun onViewCreated(view: View, state: Bundle?) { refresh() }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putString("date", selected.toString()); state.putBoolean("day", dayOnly) }
    override fun refresh() {
        title.setText(R.string.empty_loading)
        viewLifecycleOwner.runIo(block = {
            val semester = App.tis.currentSemester(); week = App.tis.currentWeek()
            runCatching { App.tis.loadSlotTimes(semester, week) }
            val classes = App.tis.semesterSchedule(semester)
            val result = runCatching { App.tis.exams() }
            Triple(classes, result.getOrDefault(emptyList()), result.isFailure)
        }, onOk = { (classes, tests, failed) -> entries = classes; exams = tests; examsFailed = failed; render() }, onErr = { title.text = it.friendly(requireContext()) })
    }
    private fun render() {
        if (!isAdded) return
        val today = LocalDate.now(zone)
        val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        val days = if (dayOnly) listOf(selected) else (0L..6L).map { monday.plusDays(it) }
        val term = AcademicCalendar.termAt(App.context, today)
        val events = Agenda.events(entries, exams, week, days, term, today)
        title.text = if (dayOnly) selected.toString() else "${days.first()} — ${days.last()}"
        body.removeAllViews()
        val windowWidth = view?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        body.addView(HorizontalScrollView(requireContext()).apply { addView(TimetableGrid(requireContext()).apply { show(days, events.distinct(), dayOnly, windowWidth) }) })
        val unknown = exams.filter { it.date.take(10) in days.map(LocalDate::toString) && Agenda.exam(it) == null }
        if (unknown.isNotEmpty() || examsFailed) body.addView(TextView(requireContext()).apply {
            setPadding(16, 12, 16, 12)
            text = if (examsFailed) getString(R.string.agenda_exam_error) else getString(R.string.agenda_exam_unknown) + "\n" + unknown.joinToString("\n") { it.course + "  " + it.room }
        })
    }
}
