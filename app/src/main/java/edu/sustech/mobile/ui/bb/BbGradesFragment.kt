package edu.sustech.mobile.ui.bb

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import edu.sustech.mobile.R
import edu.sustech.mobile.bb.BbAssessment
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.ui.SimpleAdapter

class BbGradesFragment : Fragment() {
    private var generation = 0
    private var selected = ""
    override fun onCreateView(inflater: LayoutInflater, parent: ViewGroup?, state: Bundle?): View {
        selected = state?.getString("course").orEmpty()
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), 0)
            addView(Spinner(context).apply { id = R.id.bb_grade_course; setBackgroundResource(R.drawable.paper_selector) }, LinearLayout.LayoutParams(-1, dp(48)))
            addView(TextView(context).apply { id = R.id.bb_grade_status; setText(R.string.empty_loading); setPadding(8, 12, 8, 12) })
            addView(RecyclerView(context).apply { id = R.id.bb_grade_list; layoutManager = LinearLayoutManager(context) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putString("course", selected) }
    override fun onViewCreated(view: View, state: Bundle?) {
        val status = view.findViewById<TextView>(R.id.bb_grade_status)
        val adapter = SimpleAdapter<BbAssessment>(R.layout.item_tis_course) { row, item, _ ->
            row.findViewById<TextView>(R.id.course_name).text = item.title
            row.findViewById<TextView>(R.id.course_code).text = BbAssessmentText.summary(requireContext(), item)
            row.findViewById<TextView>(R.id.course_meta).text = item.attempts.map { BbAssessmentText.plain(it.feedback) }.filter { it.isNotBlank() }.joinToString("\n").take(160)
            row.setOnClickListener {
                if (item.contentId.isNotBlank()) parentFragmentManager.beginTransaction()
                    .replace(R.id.service_container, BbItemFragment.newInstance(item.courseId, "", item.contentId)).addToBackStack("bb-grade").commit()
                else androidx.appcompat.app.AlertDialog.Builder(requireContext()).setTitle(item.title).setMessage(BbAssessmentText.detail(requireContext(), item)).setPositiveButton(android.R.string.ok, null).show()
            }
        }
        view.findViewById<RecyclerView>(R.id.bb_grade_list).adapter = adapter
        viewLifecycleOwner.runIo(block = { App.bb.courses() }, onOk = { courses ->
            if (courses.isEmpty()) { status.setText(R.string.bb_submitted_empty); return@runIo }
            view.findViewById<Spinner>(R.id.bb_grade_course).apply {
                this.adapter = ArrayAdapter(requireContext(), R.layout.paper_spinner_item, courses.map { it.name }).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                setSelection(courses.indexOfFirst { it.id == selected }.coerceAtLeast(0))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(p: AdapterView<*>?) = Unit
                    override fun onItemSelected(p: AdapterView<*>?, child: View?, index: Int, id: Long) {
                        selected = courses[index].id; val request = ++generation
                        status.setText(R.string.empty_loading); adapter.submit(emptyList())
                        viewLifecycleOwner.runIo(block = { App.bb.submittedAssessments(selected) }, onOk = { rows ->
                            if (request == generation) { adapter.submit(rows); status.setText(if (rows.isEmpty()) R.string.bb_submitted_empty else R.string.bb_submitted) }
                        }, onErr = { if (request == generation) status.text = it.friendly(requireContext()) })
                    }
                }
            }
        }, onErr = { status.text = it.friendly(requireContext()) })
    }
}
