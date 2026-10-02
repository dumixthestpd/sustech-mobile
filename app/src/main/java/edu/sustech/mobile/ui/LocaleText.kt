package edu.sustech.mobile.ui

import android.content.Context
import edu.sustech.mobile.R

fun Context.localizedWeekday(day: Int): String {
    val names = intArrayOf(
        R.string.weekday_mon, R.string.weekday_tue, R.string.weekday_wed,
        R.string.weekday_thu, R.string.weekday_fri, R.string.weekday_sat,
        R.string.weekday_sun,
    )
    return names.getOrNull(day - 1)?.let { getString(it) } ?: "?"
}

fun Context.localizedAirQuality(category: String): String {
    val name = when (category) {
        "Good" -> R.string.aqi_good
        "Moderate" -> R.string.aqi_moderate
        "Unhealthy for sensitive groups" -> R.string.aqi_sensitive
        "Unhealthy" -> R.string.aqi_unhealthy
        "Very unhealthy" -> R.string.aqi_very_unhealthy
        "Hazardous" -> R.string.aqi_hazardous
        else -> return category
    }
    return getString(name)
}
