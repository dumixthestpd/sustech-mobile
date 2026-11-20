package edu.sustech.mobile.ui.ws

import android.view.View
import android.widget.TextView
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.CheckBox
import android.os.Bundle
import androidx.core.widget.doAfterTextChanged
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.ServicePortalActivity
import edu.sustech.mobile.ws.WsApi
import edu.sustech.mobile.ws.WsProgram
import edu.sustech.mobile.ws.WsSearch

/**
 * Exchange programmes, read natively.
 *
 * The list is the part people actually browse, so it is ours: it loads with the
 * stored account and never shows a login form. Tapping a programme opens its
 * official page for the application itself — a form-heavy write that belongs on
 * the platform's own page, now with the session already established.
 */
class ExchangeFragment : ListFragment<WsProgram>(R.layout.fragment_exchange_programs) {
    private var all = emptyList<WsProgram>()
    private var query = ""
    private var year = ""
    private var region = ""
    private var type = ""
    private var eligible = false
    private var count: TextView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        query = state?.getString("query").orEmpty()
        year = state?.getString("year").orEmpty()
        region = state?.getString("region").orEmpty()
        type = state?.getString("type").orEmpty()
        eligible = state?.getBoolean("eligible") ?: false
    }

    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putString("query", query); state.putString("year", year)
        state.putString("region", region); state.putString("type", type); state.putBoolean("eligible", eligible)
    }

    override fun onReady(view: View) {
        count = view.findViewById(R.id.ws_results)
        view.findViewById<EditText>(R.id.ws_search).apply {
            setText(query)
            doAfterTextChanged { query = it?.toString().orEmpty(); applyFilters() }
        }
        view.findViewById<CheckBox>(R.id.ws_eligible).apply {
            isChecked = eligible
            setOnCheckedChangeListener { _, checked -> eligible = checked; applyFilters() }
        }
        view.findViewById<View>(R.id.ws_clear).setOnClickListener {
            year = ""; region = ""; type = ""
            view.findViewById<EditText>(R.id.ws_search).setText("")
            view.findViewById<CheckBox>(R.id.ws_eligible).isChecked = false
            buildFacets(view); applyFilters()
        }
    }

    override fun rowLayout() = R.layout.item_ws_program

    override fun cachePrefix() = "ws.programs."

    override fun emptyText() = getString(R.string.ws_no_programs)

    override suspend fun fetch(): List<WsProgram> = App.ws.catalog()

    override fun onLoaded(rows: List<WsProgram>) {
        all = rows
        view?.let { buildFacets(it) }
        applyFilters()
    }

    private fun buildFacets(view: View) {
        facet(view, R.id.ws_year, R.string.ws_year_all, all.map { it.year }.sortedDescending(), year) { year = it }
        facet(view, R.id.ws_region, R.string.ws_region_all, all.map { it.region }.sorted(), region) { region = it }
        facet(view, R.id.ws_type, R.string.ws_type_all, all.map { it.type }.sorted(), type) { type = it }
    }

    private fun facet(view: View, id: Int, title: Int, values: List<String>, current: String, set: (String) -> Unit) {
        val options = listOf("") + values.filter { it.isNotBlank() }.distinct()
        view.findViewById<Spinner>(id).apply {
            onItemSelectedListener = null
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                options.map { it.ifEmpty { getString(title) } })
            val selected = options.indexOf(current).coerceAtLeast(0)
            setSelection(selected)
            set(options[selected])
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, id: Long) {
                    set(options[position]); applyFilters()
                }
            }
        }
    }

    private fun applyFilters() {
        if (!isAdded) return
        val rows = WsSearch.filter(all, query, year, region, type, eligible)
        adapter.submit(rows)
        showEmpty(rows.isEmpty(), emptyText())
        count?.text = getString(R.string.ws_search_count, rows.size, all.size)
    }

    override fun bindRow(view: View, item: WsProgram, position: Int) {
        view.findViewById<TextView>(R.id.ws_program_name).text = item.name

        val where = listOf(item.region, item.school, item.type)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val meta = view.findViewById<TextView>(R.id.ws_program_where)
        meta.text = where
        meta.visibility = if (where.isBlank()) View.GONE else View.VISIBLE

        val whenLine = listOf(item.status, item.applyRange)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val status = view.findViewById<TextView>(R.id.ws_program_status)
        status.text = whenLine
        status.visibility = if (whenLine.isBlank()) View.GONE else View.VISIBLE
        status.setTextColor(
            requireContext().getColor(if (item.appliable) R.color.brand else R.color.ink_muted),
        )

        view.setOnClickListener {
            startActivity(ServicePortalActivity.intent(requireContext(), WsApi.detailUrl(item)))
        }
    }
}
