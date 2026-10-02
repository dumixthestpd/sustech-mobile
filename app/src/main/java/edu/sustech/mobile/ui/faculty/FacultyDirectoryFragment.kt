package edu.sustech.mobile.ui.faculty

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import edu.sustech.mobile.R
import edu.sustech.mobile.ui.ServicePortalActivity
import edu.sustech.mobile.ui.SimpleAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

/** Searches a bundled snapshot of the university's public faculty directory. */
class FacultyDirectoryFragment : Fragment(R.layout.fragment_faculty_directory) {

    private lateinit var adapter: SimpleAdapter<Person>
    private lateinit var search: TextInputEditText
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var sourceNote: TextView
    private var allPeople: List<Person> = emptyList()
    private val chinese: Boolean
        get() = resources.configuration.locales[0].language == "zh"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        search = view.findViewById(R.id.faculty_search)
        count = view.findViewById(R.id.faculty_count)
        empty = view.findViewById(R.id.faculty_empty)
        val list = view.findViewById<RecyclerView>(R.id.faculty_list)
        adapter = SimpleAdapter(R.layout.item_faculty_directory) { row, person, _ ->
            row.findViewById<TextView>(R.id.faculty_name).text = person.displayName(chinese)
            row.findViewById<TextView>(R.id.faculty_details).text = person.displayDetails(chinese)
            row.setOnClickListener {
                startActivity(ServicePortalActivity.intent(requireContext(), person.profileUrl))
            }
        }
        list.layoutManager = LinearLayoutManager(requireContext())
        list.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                showMatches(s?.toString().orEmpty())
            override fun afterTextChanged(s: Editable?) = Unit
        })

        sourceNote = view.findViewById(R.id.faculty_source_note)
        sourceNote.text = getString(R.string.faculty_source_note, "—")
        val appContext = requireContext().applicationContext

        viewLifecycleOwner.lifecycleScope.launch {
            val directory = withContext(Dispatchers.IO) { loadBundledDirectory(appContext) }
            if (!isAdded) return@launch
            allPeople = directory.people
            sourceNote.text = getString(R.string.faculty_source_note, directory.generatedAt)
            showMatches(search.text?.toString().orEmpty())
        }
    }

    private fun showMatches(query: String) {
        val needle = query.trim().lowercase(Locale.ROOT)
        val matches = if (needle.isEmpty()) allPeople else allPeople.filter { person ->
            person.searchText.contains(needle)
        }
        adapter.submit(matches)
        count.text = getString(R.string.faculty_count, matches.size)
        empty.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        empty.text = getString(if (needle.isEmpty()) R.string.faculty_empty else R.string.faculty_no_results)
    }

    private fun loadBundledDirectory(context: android.content.Context): DirectorySnapshot {
        val json = context.assets.open("faculty_directory.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val directory = JSONObject(json)
        val records = directory.getJSONArray("records")
        val people = buildList(records.length()) {
            for (index in 0 until records.length()) {
                val entry = records.getJSONObject(index)
                val person = Person(
                    nameZh = entry.optString("nameZh"),
                    nameEn = entry.optString("nameEn"),
                    titleZh = entry.optString("titleZh"),
                    titleEn = entry.optString("titleEn"),
                    departmentZh = entry.optString("departmentZh"),
                    departmentEn = entry.optString("departmentEn"),
                    profileUrl = entry.optString("url"),
                )
                if (person.nameZh.isNotBlank() && isOfficialProfile(person.profileUrl)) add(person)
            }
        }
        return DirectorySnapshot(directory.optString("generatedAt", "—"), people)
    }

    private fun isOfficialProfile(url: String): Boolean =
        url.startsWith("https://www.sustech.edu.cn/zh/faculties/") &&
            !url.substringAfterLast('/').contains("..")

    private data class Person(
        val nameZh: String,
        val nameEn: String,
        val titleZh: String,
        val titleEn: String,
        val departmentZh: String,
        val departmentEn: String,
        val profileUrl: String,
    ) {
        val searchText = listOf(
            nameZh, nameEn, titleZh, titleEn, departmentZh, departmentEn,
        ).joinToString(" ").lowercase(Locale.ROOT)

        fun displayName(chinese: Boolean): String =
            if (chinese || nameEn.isBlank()) nameZh else nameEn

        fun displayDetails(chinese: Boolean): String {
            val title = if (chinese) titleZh else titleEn.takeUnless { it.any(::isChinese) }.orEmpty()
            val department = if (chinese) departmentZh else departmentEn
            return listOf(title, department).filter { it.isNotBlank() }.joinToString(" · ")
        }

        private fun isChinese(character: Char): Boolean = character in '\u4e00'..'\u9fff'
    }

    private data class DirectorySnapshot(val generatedAt: String, val people: List<Person>)
}
