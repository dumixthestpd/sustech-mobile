package edu.sustech.mobile.ui.tis

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import edu.sustech.mobile.R
import edu.sustech.mobile.core.*
import edu.sustech.mobile.calendar.AcademicCalendar
import edu.sustech.mobile.tis.*
import edu.sustech.mobile.ui.Refreshable
import java.time.*
import kotlinx.coroutines.*

/** Home is today's complete schedule; the full week remains in Courses. */
class HomeDayFragment : Fragment(), Refreshable {
    private val zone = ZoneId.of("Asia/Shanghai")
    private var date = LocalDate.now(zone)
    private var events = emptyList<AgendaEvent>()
    private var ready = false
    private var examsFailed = false
    private lateinit var body: LinearLayout
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.paper_panel); tag = "palette_exempt"; body = this
        }
    override fun onViewCreated(view: View, state: Bundle?) {
        refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) { if (LocalDate.now(zone) != date) refresh() else if (ready) render(); delay(30_000) }
            }
        }
    }
    override fun refresh() {
        date = LocalDate.now(zone); ready = false
        body.removeAllViews(); body.addView(TextView(requireContext()).apply { setText(R.string.empty_loading) })
        viewLifecycleOwner.runIo(block = {
            val semester = App.tis.currentSemester(); val week = App.tis.currentWeek()
            runCatching { App.tis.loadSlotTimes(semester, week) }
            val entries = App.tis.semesterSchedule(semester)
            val exams = runCatching { App.tis.exams() }
            Agenda.events(entries, exams.getOrDefault(emptyList()), week, listOf(date), AcademicCalendar.termAt(App.context, date), date) to exams.isFailure
        }, onOk = { (rows, failed) ->
            events = rows; examsFailed = failed; ready = true
            body.post { if (isAdded && this@HomeDayFragment.view != null) render() }
        }, onErr = { error -> body.removeAllViews(); body.addView(TextView(requireContext()).apply { text = error.friendly(requireContext()) }) })
    }
    private fun render() {
        if (!isAdded || body.width <= 0) return
        body.removeAllViews()
        if (events.isEmpty()) body.addView(TextView(requireContext()).apply { setText(R.string.today_no_classes); setPadding(12, 8, 12, 8) })
        body.addView(TimetableGrid(requireContext()).apply { tag = "palette_exempt"; show(listOf(date), events, true, body.width, true, LocalTime.now(zone)) })
        if (examsFailed) body.addView(TextView(requireContext()).apply { setText(R.string.agenda_exam_error) })
    }
}
