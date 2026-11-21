package edu.sustech.mobile.ui

import android.content.Context

/** Local presentation preferences; IDs survive translations and registry changes. */
data class HomeLayout(
    val hero: String = "ddl",
    val order: List<String> = MODULES,
    val visible: Set<String> = setOf("ddl", "schedule", "services", "weather", "exams"),
    val services: List<String> = DEFAULT_SERVICES,
    val landing: String = "home",
    val schedule: String = "day",
    val emphasis: Boolean = true,
) {
    fun displayed(): List<String> = (listOf(hero) + order).filter { it in visible && it in MODULES }.distinct()

    companion object {
        val MODULES = listOf("ddl", "schedule", "bus", "services", "weather", "exams")
        val DEFAULT_SERVICES = listOf("blackboard", "transit", "tis", "rooms", "pms", "exchange", "library", "booking")
        fun normalize(value: HomeLayout, knownServices: List<String>): HomeLayout = value.copy(
            hero = value.hero.takeIf { it in MODULES && it in value.visible }.orEmpty(),
            order = (value.order + MODULES).filter { it in MODULES }.distinct(),
            visible = value.visible.intersect(MODULES.toSet()),
            services = value.services.filter { it in knownServices }.distinct(),
            landing = value.landing.takeIf { it in listOf("home", "bb", "bus") } ?: "home",
            schedule = "day",
        )
    }
}

object HomePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("home_layout", Context.MODE_PRIVATE)
    fun read(context: Context): HomeLayout {
        val p = prefs(context)
        val value = HomeLayout(
            hero = p.getString("hero", "ddl").orEmpty(),
            order = p.getString("order", HomeLayout.MODULES.joinToString(",")).orEmpty().split(','),
            visible = p.getString("visible", HomeLayout().visible.joinToString(",")).orEmpty().split(',').toSet(),
            services = p.getString("services", HomeLayout.DEFAULT_SERVICES.joinToString(",")).orEmpty().split(','),
            landing = p.getString("landing", "home").orEmpty(),
            schedule = "day",
            emphasis = p.getBoolean("emphasis", true),
        )
        return HomeLayout.normalize(value, edu.sustech.mobile.service.Services.all.map { it.id })
    }
    fun write(context: Context, value: HomeLayout) {
        val v = HomeLayout.normalize(value, edu.sustech.mobile.service.Services.all.map { it.id })
        prefs(context).edit().putString("hero", v.hero).putString("order", v.order.joinToString(","))
            .putString("visible", v.visible.joinToString(",")).putString("services", v.services.joinToString(","))
            .putString("landing", v.landing).putString("schedule", v.schedule).putBoolean("emphasis", v.emphasis).apply()
    }
    fun reset(context: Context) {
        val editor = prefs(context).edit()
        listOf("hero", "order", "visible", "services", "landing", "schedule", "emphasis").forEach { editor.remove(it) }
        editor.apply()
    }
    fun busSelection(context: Context): Pair<String, String> = prefs(context).let {
        it.getString("bus_stop", "").orEmpty() to it.getString("bus_direction", "").orEmpty()
    }
    fun busSelection(context: Context, stop: String, direction: String) {
        prefs(context).edit().putString("bus_stop", stop).putString("bus_direction", direction).apply()
    }
}
