package edu.sustech.mobile.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
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

/** Immediately saved presentation choices; no campus writes or sample academic records. */
class HomeSettingsFragment : Fragment(R.layout.fragment_home_settings) {
    private lateinit var value: HomeLayout
    private var servicesTab = false
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onViewCreated(view: View, state: Bundle?) {
        value = HomePreferences.read(requireContext())
        servicesTab = state?.getBoolean("services") ?: arguments?.getBoolean("services") ?: false
        view.findViewById<ChipGroup>(R.id.home_settings_tabs).apply {
            check(if (servicesTab) R.id.home_settings_services_tab else R.id.home_settings_home_tab)
            setOnCheckedStateChangeListener { _, checked ->
                servicesTab = R.id.home_settings_services_tab in checked
                panels(); preview()
            }
        }
        val presets = view.findViewById<ChipGroup>(R.id.home_presets)
        listOf("ddl" to R.string.home_preset_ddl, "bus" to R.string.home_preset_bus,
            "campus" to R.string.home_preset_campus).forEach { (id, title) ->
            presets.addView(Chip(requireContext()).apply {
                text = getString(title)
                setOnClickListener {
                    val hero = when (id) { "bus" -> "bus"; "campus" -> "schedule"; else -> "ddl" }
                    save(value.copy(hero = hero, visible = HomeLayout.MODULES.toSet(),
                        order = (listOf(hero) + HomeLayout.MODULES).distinct()))
                }
            })
        }
        view.findViewById<View>(R.id.home_layout_reset).setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle(R.string.home_layout_reset)
                .setMessage(R.string.home_layout_reset_question)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    HomePreferences.reset(requireContext()); value = HomePreferences.read(requireContext()); render()
                }.show()
        }
        render()
    }

    override fun onSaveInstanceState(state: Bundle) { super.onSaveInstanceState(state); state.putBoolean("services", servicesTab) }

    private fun save(next: HomeLayout, rebuild: Boolean = true) {
        HomePreferences.write(requireContext(), next)
        value = HomePreferences.read(requireContext())
        if (rebuild) render() else preview()
    }

    private fun panels() {
        view?.findViewById<View>(R.id.home_settings_home_panel)?.visibility = if (servicesTab) View.GONE else View.VISIBLE
        view?.findViewById<View>(R.id.home_settings_services_panel)?.visibility = if (servicesTab) View.VISIBLE else View.GONE
    }

    private fun render() {
        val root = view ?: return
        panels(); preview()
        selector(root, R.id.home_landing, listOf("home", "bb", "bus"), value.landing,
            listOf(R.string.home_landing_home, R.string.bb_deadlines, R.string.service_transit).map(::getString)) { save(value.copy(landing = it), false) }
        val heroes = listOf("") + HomeLayout.MODULES
        selector(root, R.id.home_hero, heroes, value.hero,
            heroes.map { if (it.isEmpty()) getString(R.string.home_layout_follow_order) else getString(homeModuleTitle(it)) }) {
            save(value.copy(hero = it, visible = if (it.isEmpty()) value.visible else value.visible + it))
        }
        root.findViewById<SwitchMaterial>(R.id.home_emphasis).apply {
            setOnCheckedChangeListener(null); isChecked = value.emphasis
            setOnCheckedChangeListener { _, checked -> save(value.copy(emphasis = checked), false) }
        }
        val modules = root.findViewById<LinearLayout>(R.id.home_module_rows)
        modules.removeAllViews()
        value.order.forEachIndexed { index, id ->
            editableRow(modules, id, getString(homeModuleTitle(id)), id in value.visible,
                index > 0 && id != value.hero, index < value.order.lastIndex && id != value.hero,
                toggle = { enabled -> save(value.copy(visible = if (enabled) value.visible + id else value.visible - id,
                    hero = if (!enabled && value.hero == id) "" else value.hero)) },
                move = { step -> save(value.copy(order = moved(value.order, index, step))) })
        }
        val shortcuts = root.findViewById<LinearLayout>(R.id.home_service_rows)
        shortcuts.removeAllViews()
        val services = (value.services + Services.all.map { it.id }).distinct()
        services.forEach { id ->
            val module = Services.byId(id) ?: return@forEach
            val index = value.services.indexOf(id)
            editableRow(shortcuts, id, getString(module.title), index >= 0,
                index > 0, index >= 0 && index < value.services.lastIndex,
                toggle = { enabled -> save(value.copy(services = if (enabled) value.services + id else value.services - id)) },
                move = { step -> save(value.copy(services = moved(value.services, index, step))) })
        }
    }

    private fun moved(list: List<String>, index: Int, step: Int): List<String> = list.toMutableList().apply {
        val target = index + step
        if (index in indices && target in indices) add(target, removeAt(index))
    }

    private fun selector(root: View, id: Int, keys: List<String>, selected: String, labels: List<String>, change: (String) -> Unit) {
        root.findViewById<Spinner>(id).apply {
            onItemSelectedListener = null
            adapter = ArrayAdapter(requireContext(), R.layout.paper_spinner_item, labels).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(keys.indexOf(selected).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, child: View?, position: Int, row: Long) {
                    if (keys[position] != selected) change(keys[position])
                }
            }
        }
    }

    private fun editableRow(parent: LinearLayout, key: String, title: String, enabled: Boolean,
        up: Boolean, down: Boolean, toggle: (Boolean) -> Unit, move: (Int) -> Unit) {
        val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(60); tag = key }
        row.addView(SwitchMaterial(requireContext()).apply {
            text = title; textSize = 14f; isChecked = enabled; tag = "toggle:$key"
            setTextColor(requireContext().getColor(R.color.ink))
            setOnCheckedChangeListener { _, checked -> toggle(checked) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        listOf(-1 to up, 1 to down).forEach { (step, allowed) ->
            row.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = if (step < 0) "↑" else "↓"; textSize = 20f
                setPadding(0, 0, 0, 0); minimumWidth = 0
                contentDescription = getString(if (step < 0) R.string.home_layout_move_up else R.string.home_layout_move_down, title)
                tag = "${if (step < 0) "up" else "down"}:$key"
                isEnabled = allowed; setOnClickListener { move(step) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        parent.addView(row)
        parent.addView(View(requireContext()).apply { setBackgroundColor(requireContext().getColor(R.color.divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun preview() {
        val preview = view?.findViewById<LinearLayout>(R.id.home_layout_preview) ?: return
        preview.removeAllViews()
        val labels = if (servicesTab) value.services.mapNotNull { Services.byId(it) }.map { getString(it.title) }
            else value.displayed().map { getString(homeModuleTitle(it)) }
        if (servicesTab) {
            val group = ChipGroup(requireContext())
            labels.forEach { name -> group.addView(Chip(requireContext()).apply { text = name; isClickable = false }) }
            preview.addView(group)
        } else labels.forEachIndexed { index, name ->
            preview.addView(TextView(requireContext()).apply {
                text = name; textSize = if (index == 0) 16f else 12f
                setPadding(dp(12), dp(8), dp(12), dp(8)); setBackgroundResource(if (index == 0 && value.emphasis) R.drawable.paper_preview_hero else R.drawable.paper_input)
                setTextColor(requireContext().getColor(if (index == 0 && value.emphasis) R.color.white else R.color.ink))
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        if (labels.isEmpty()) preview.addView(TextView(requireContext()).apply { setText(R.string.home_layout_empty) })
    }
}
