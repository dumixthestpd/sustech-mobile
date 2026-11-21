package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import edu.sustech.mobile.R
import edu.sustech.mobile.service.Services

fun homeModuleTitle(id: String): Int = when (id) {
    "ddl" -> R.string.home_layout_deadlines
    "bus" -> R.string.service_transit
    "schedule" -> R.string.home_layout_course_card
    "services" -> R.string.ui_shortcuts
    "weather" -> R.string.home_layout_weather
    else -> R.string.today_next_exam
}

/** Account owns settings. The draggable list itself is the ordered preview. */
class HomeSettingsFragment : Fragment(R.layout.fragment_home_settings) {
    private lateinit var value: HomeLayout
    private lateinit var rows: HomeReorderAdapter
    private var servicesTab = false
    override fun onViewCreated(view: View, state: Bundle?) {
        value = HomePreferences.read(requireContext())
        // Preserve the effective legacy order, then remove its independent first-card override.
        value = value.copy(order = (listOf(value.hero) + value.order).filter { it in HomeLayout.MODULES }.distinct(), hero = "")
        HomePreferences.write(requireContext(), value)
        servicesTab = state?.getBoolean("services") ?: arguments?.getBoolean("services") ?: false
        val list = view.findViewById<RecyclerView>(R.id.home_reorder_list)
        list.layoutManager = LinearLayoutManager(requireContext())
        rows = HomeReorderAdapter { changed ->
            value = if (servicesTab) value.copy(services = changed.filter { it.enabled }.map { it.key })
            else value.copy(hero = "", order = changed.map { it.key }, visible = changed.filter { it.enabled }.map { it.key }.toSet())
            HomePreferences.write(requireContext(), value)
        }
        rows.attach(list)
        view.findViewById<ChipGroup>(R.id.home_settings_tabs).apply {
            check(if (servicesTab) R.id.home_settings_services_tab else R.id.home_settings_home_tab)
            setOnCheckedStateChangeListener { _, checked -> servicesTab = R.id.home_settings_services_tab in checked; renderRows() }
        }
        view.findViewById<Spinner>(R.id.home_landing).apply {
            val keys = listOf("home", "bb", "bus")
            adapter = ArrayAdapter(requireContext(), R.layout.paper_spinner_item,
                listOf(R.string.home_landing_home, R.string.bb_deadlines, R.string.service_transit).map(::getString)).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(keys.indexOf(value.landing).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, id: Long) {
                    value = value.copy(landing = keys[position]); HomePreferences.write(requireContext(), value)
                }
            }
        }
        view.findViewById<SwitchMaterial>(R.id.home_emphasis).apply {
            isChecked = value.emphasis
            setOnCheckedChangeListener { _, checked -> value = value.copy(emphasis = checked); HomePreferences.write(requireContext(), value) }
        }
        view.findViewById<View>(R.id.home_layout_reset).setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle(R.string.home_layout_reset).setMessage(R.string.home_layout_reset_question)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                    HomePreferences.reset(requireContext()); value = HomePreferences.read(requireContext()).copy(hero = "")
                    HomePreferences.write(requireContext(), value)
                    view.findViewById<Spinner>(R.id.home_landing).setSelection(0)
                    view.findViewById<SwitchMaterial>(R.id.home_emphasis).isChecked = value.emphasis
                    renderRows()
                }.show()
        }
        renderRows()
    }
    private fun renderRows() {
        view?.findViewById<View>(R.id.home_settings_home_panel)?.visibility = if (servicesTab) View.GONE else View.VISIBLE
        rows.replace(if (servicesTab) (value.services + Services.all.map { it.id }).distinct().mapNotNull { key ->
            Services.byId(key)?.let { HomeReorderAdapter.Row(key, getString(it.title), key in value.services) }
        } else value.order.map { HomeReorderAdapter.Row(it, getString(homeModuleTitle(it)), it in value.visible) })
        view?.findViewById<RecyclerView>(R.id.home_reorder_list)?.scrollToPosition(0)
    }
    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putBoolean("services", servicesTab) }
}
