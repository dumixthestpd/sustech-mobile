package edu.sustech.mobile.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import edu.sustech.mobile.R
import edu.sustech.mobile.calendar.AcademicCalendar
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.ecard.EcardApi
import edu.sustech.mobile.sso.Session
import edu.sustech.mobile.tis.NextClass
import edu.sustech.mobile.tis.PeriodTimes
import edu.sustech.mobile.ui.localizedAirQuality
import edu.sustech.mobile.ui.localizedWeekday
import edu.sustech.mobile.transit.BusApi
import edu.sustech.mobile.transit.LocationProbe
import edu.sustech.mobile.transit.TransitText
import java.time.LocalDate
import java.util.Calendar

/**
 * What a widget shows, whatever it is configured for.
 *
 * The home screen can only render [RemoteViews], so every widget is reduced to
 * a title, a short list of lines, and the time the data was fetched — the
 * fetching and the thinking happen here, off the widget's thread, and the
 * provider only paints. [at] is what lets the card say how stale it is.
 */
data class WidgetSnapshot(
    val title: String,
    val lines: List<String>,
    val at: Long = System.currentTimeMillis(),
)

/** The widget contents the config screen offers. */
enum class WidgetKind(val key: String) {
    BUS("bus"),
    DEADLINES("bb"),
    CLASSES("classes"),
    WEATHER("weather"),
    // Kept only to recognize old configurable QR widgets and replace them
    // with a message. New campus QR widgets use their own 2x2 provider.
    CAMPUS_CARD_QR("ecard");

    companion object {
        fun of(key: String?): WidgetKind = entries.firstOrNull { it.key == key } ?: BUS
    }

    /**
     * How long a card of this kind may go without a network refresh.
     *
     * A bus moves between stops in minutes; a deadline does not move at all.
     * Polling each widget at the bus's pace burned the campus API and the TIS
     * session for no gain, so the slow kinds are refreshed on a slow clock and
     * the fast one on a fast clock.
     */
    val cadenceMillis: Long
        get() = when (this) {
            BUS -> 60_000L
            DEADLINES, CLASSES, WEATHER, CAMPUS_CARD_QR -> 15 * 60_000L
        }
}

/**
 * Builds the snapshot for one widget.
 *
 * These functions **throw** on failure instead of inventing an error card: the
 * provider owns the fallback (last stored snapshot + its age), because only it
 * knows what the widget showed a minute ago.
 */
object WidgetData {

    private const val QR_SIZE = 320

    fun snapshot(context: Context, kind: WidgetKind, stopId: String): WidgetSnapshot = when (kind) {
        WidgetKind.BUS -> bus(context, stopId)
        WidgetKind.DEADLINES -> deadlines(context)
        WidgetKind.CLASSES -> classes(context)
        WidgetKind.WEATHER -> weather(context)
        WidgetKind.CAMPUS_CARD_QR -> WidgetSnapshot(
            titleOf(context, kind),
            listOf(
                context.getString(R.string.widget_ecard_moved),
                context.getString(R.string.widget_ecard_moved_action),
            ),
        )
    }

    fun titleOf(context: Context, kind: WidgetKind): String = when (kind) {
        WidgetKind.BUS -> context.getString(R.string.widget_bus_title)
        WidgetKind.DEADLINES -> context.getString(R.string.widget_deadlines_title)
        WidgetKind.CLASSES -> context.getString(R.string.widget_kind_classes)
        WidgetKind.WEATHER -> context.getString(R.string.today_weather)
        WidgetKind.CAMPUS_CARD_QR -> context.getString(R.string.widget_ecard_title)
    }

    /** One short line explaining a failure, for the card's footer. */
    fun shortReason(context: Context, error: Throwable): String = when {
        error is ApiException && error.signInRequired -> context.getString(R.string.widget_sign_in_reason)
        error is ApiException && error.offCampus -> context.getString(R.string.widget_campus_reason)
        error is ApiException && error.message.orEmpty().contains("校园网") ->
            context.getString(R.string.widget_campus_reason)
        else -> context.getString(R.string.widget_failed_reason)
    }

    /** Fetches and encodes the live campus QR without returning or persisting its payload. */
    fun campusCardQrBitmap(context: Context): Bitmap {
        val payload = try {
            App.ecard.qrText(EcardApi.QrKind.CAMPUS)
        } catch (error: ApiException) {
            if (!error.signInRequired || !Session.reloginCard()) throw error
            App.ecard.qrText(EcardApi.QrKind.CAMPUS)
        }
        if (payload.isBlank()) throw ApiException(context.getString(R.string.widget_ecard_empty))

        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints)
        val pixels = IntArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            pixels[y * matrix.width + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.RGB_565)
    }

    /**
     * Bus ETAs for a stop — the "wait or run" card.
     *
     * With nothing configured, the stop is the one nearest the phone: the card
     * exists for the moment you are walking and want to know whether to run,
     * and at that moment the nearest stop is the only one that matters. Each
     * line names the first stop after this one, because a direction word
     * alone does not tell you whether that bus goes to the dorm or to the
     * engineering college.
     */
    private fun bus(context: Context, stopId: String): WidgetSnapshot {
        val stops = App.bus.stops()
        val here = LocationProbe.lastKnown(context)
        val nearest = here?.let {
            App.bus.nearestStops(it.latitude, it.longitude, 1).firstOrNull()
        }
        val configured = stops.firstOrNull { it.id == stopId }
        val stop: BusApi.Stop
        val metres: Int?
        when {
            configured != null -> { stop = configured; metres = null }
            nearest != null -> { stop = nearest.first; metres = nearest.second }
            else -> {
                stop = stops.firstOrNull()
                    ?: return WidgetSnapshot(titleOf(context, WidgetKind.BUS), listOf(context.getString(R.string.widget_no_stop_data)))
                metres = null
            }
        }

        val arrivals = App.bus.arrivals(stop.id)
        val lines = when {
            arrivals.isEmpty() -> listOf(context.getString(R.string.widget_no_buses))
            // Every entry carries no live estimate (last bus gone, holiday):
            // say why, instead of a row of dashes the reader must decode.
            arrivals.all { it.minutes == null } -> arrivals.take(2).map { arrival ->
                context.getString(
                    R.string.transit_widget_reason,
                    arrival.routeName,
                    TransitText.reason(context, arrival.reason),
                )
            }
            else -> arrivals.take(3).map { arrival ->
                val route = arrival.routeName.ifEmpty { titleOf(context, WidgetKind.BUS) }
                val tail = arrival.upcoming.firstOrNull()
                    ?.let { context.getString(R.string.transit_next_short, it) }.orEmpty()
                val eta = TransitText.eta(context, arrival)
                context.getString(R.string.transit_widget_arrival, route, eta, tail)
            }
        }
        val away = metres?.let {
            " · " + TransitText.distance(context, it)
        }
        return WidgetSnapshot(stop.display + (away ?: ""), lines)
    }

    /**
     * The next few Blackboard deadlines.
     *
     * `allowRelogin = false`: a widget refresh must never drive a CAS sign-in.
     * Every 60 seconds of self-healing produced throttled CAS answers, which
     * read as "no connection" on the card; if the session is gone, the widget
     * says so and the app does the signing in.
     */
    private fun deadlines(context: Context): WidgetSnapshot {
        val all = App.bb.deadlines(allowRelogin = false)
        val lines = if (all.isEmpty()) listOf(context.getString(R.string.widget_no_deadlines))
        else all.take(3).map { "${it.due}  ${it.title.take(26)}" }
        return WidgetSnapshot(titleOf(context, WidgetKind.DEADLINES), lines)
    }

    /**
     * The next meeting on the real calendar — the same rule the app's Today
     * card uses (see [NextClass]), so the widget and the app never disagree.
     */
    private fun classes(context: Context): WidgetSnapshot {
        val semester = App.tis.currentSemester()
        val week = App.tis.currentWeek()
        val entries = App.tis.semesterSchedule(semester)
        val today = LocalDate.now()
        val currentPeriod = PeriodTimes.currentPeriod(Calendar.getInstance())
        val term = AcademicCalendar.termAt(context, today)
        val next = if (term != null) {
            NextClass.find(entries, term, today, currentPeriod)
        } else {
            NextClass.findInWeek(entries, week, today, currentPeriod)
        }

        if (next == null) return WidgetSnapshot(titleOf(context, WidgetKind.CLASSES), listOf(context.getString(R.string.widget_no_classes)))

        val prefix = if (next.date == today) "" else "${context.localizedWeekday(next.date.dayOfWeek.value)} "
        val time = next.entry.timeText.ifEmpty {
            PeriodTimes.range(next.entry.periodFrom, next.entry.periodTo)
        }
        val lines = buildList {
            add("$prefix$time  ${next.entry.name}")
            if (next.entry.room.isNotEmpty()) add(next.entry.room)
        }
        return WidgetSnapshot(titleOf(context, WidgetKind.CLASSES), lines)
    }

    /** Campus weather + AQI, the two numbers worth a glance before leaving. */
    private fun weather(context: Context): WidgetSnapshot {
        val current = App.weather.weather()
        val air = runCatching { App.weather.airQuality() }.getOrNull()
        val temp = current.tempC?.let { "$it°C" } ?: "—"
        val feels = current.feelsLike?.let { context.getString(R.string.weather_feels_like, it) } ?: ""
        val rain = context.getString(if (current.rainExpected) R.string.widget_rain_expected else R.string.widget_no_rain)
        val lines = buildList {
            add(listOf(temp, feels).filter { it.isNotEmpty() }.joinToString(" · "))
            add(rain)
            air?.aqi?.let { add("AQI $it ${context.localizedAirQuality(air.category)}") }
        }
        return WidgetSnapshot(titleOf(context, WidgetKind.WEATHER), lines)
    }
}
