package edu.sustech.mobile.transit

import android.content.Context
import edu.sustech.mobile.R
import java.util.Locale

/** Localized presentation for live bus data and server reason codes. */
object TransitText {

    fun eta(context: Context, arrival: BusApi.Arrival): String = when {
        arrival.minutes != null && arrival.minutes <= 0 -> context.getString(R.string.transit_arriving)
        arrival.minutes != null -> context.resources.getQuantityString(
            R.plurals.transit_eta_minutes,
            arrival.minutes,
            arrival.minutes,
        )
        arrival.plannedAt.isNotEmpty() -> arrival.plannedAt
        else -> reason(context, arrival.reason)
    }

    fun source(context: Context, arrival: BusApi.Arrival): String = when (arrival.source.lowercase()) {
        "real_time" -> context.getString(R.string.transit_live)
        "planned" -> context.getString(R.string.transit_timetable)
        else -> ""
    }

    fun reason(context: Context, code: String): String = when (code.uppercase()) {
        "LAST_SERVICE_PASSED" -> context.getString(R.string.transit_last_bus_gone)
        "NOT_OPERATING" -> context.getString(R.string.transit_not_operating_today)
        else -> context.getString(R.string.transit_no_service)
    }

    fun position(context: Context, arrival: BusApi.Arrival): String {
        val away = arrival.stopsAway ?: return ""
        if (away <= 0) return context.getString(R.string.transit_bus_at_stop)
        val remaining = context.resources.getQuantityString(
            R.plurals.transit_stops_remaining,
            away,
            away,
        )
        return if (arrival.nextBusStop.isBlank()) remaining
        else context.getString(R.string.transit_next_stop, remaining, arrival.nextBusStop)
    }

    fun distance(context: Context, metres: Int): String {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val value = if (metres < 1000) "$metres m" else String.format(locale, "%.1f km", metres / 1000.0)
        return context.getString(R.string.transit_distance_away, value)
    }

    fun direction(context: Context, value: String): String = when (value.trim().lowercase()) {
        "cw", "clockwise", "顺时针" -> context.getString(R.string.transit_direction_cw)
        "ccw", "counter-clockwise", "counterclockwise", "逆时针" ->
            context.getString(R.string.transit_direction_ccw)
        "uphill", "上坡" -> context.getString(R.string.transit_direction_uphill)
        "downhill", "下坡" -> context.getString(R.string.transit_direction_downhill)
        else -> value
    }

    /** The stop list can combine line numbers and English direction names. */
    fun serviceLines(context: Context, labels: List<String>): String =
        labels.joinToString(" · ") { label ->
            label.split('/').joinToString("/") { direction(context, it) }
        }
}
