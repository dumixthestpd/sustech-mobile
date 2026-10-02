package edu.sustech.mobile.transit

import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.Cache
import edu.sustech.mobile.sso.CasLogin
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Campus shuttle data, from the public bus API behind sustech.online's
 * `transport/bustimer.html`.
 *
 * `https://buseta.sustcra.com` is a plain, unauthenticated JSON service run by
 * SUSTech CRA — the site's own page calls exactly these endpoints:
 *
 *   GET /stops                 → every stop (id, group + name, coordinates)
 *   GET /arrivals/{stop_id}    → ETAs at one stop (minutes, distance, source)
 *   GET /routes                → lines with colour, service window, stop order
 *   GET /vehicles              → live buses with GPS position and next stop
 *   GET /notices               → service announcements (extra runs, changes)
 *
 * Every entity comes in `_zh` and `_en` fields; this client follows the app
 * language (Russian falls back to English). Only the
 * HTTP contract is borrowed; no site code is copied. The site is CC BY-SA 4.0,
 * so reusing its JavaScript would drag its ShareAlike terms into this app —
 * the endpoints and their JSON shape are facts, and the client below is ours.
 * Credit stays visible in the UI.
 */
class BusApi(
    private val http: OkHttpClient,
    private val language: () -> String = { "en" },
) {

    private val languageTag: String
        get() = if (language().startsWith("zh", ignoreCase = true)) "zh" else "en"

    private fun localized(json: JSONObject, field: String): String {
        val preferred = json.optString("${field}_${languageTag}")
        val englishFallback = if (languageTag == "zh") json.optString("${field}_en") else ""
        return preferred.ifBlank { englishFallback }.ifBlank { json.optString(field) }
    }

    /**
     * One direction of one line calling at a stop.
     *
     * A stop is a *berth*, and a berth is a specific direction of a line — so
     * this is the only thing that says what actually stops here.
     */
    data class Serve(
        val routeId: String,
        val routeName: String,
        val directionId: String,
        val directionName: String,
        val color: String,
    ) {
        /** "Clockwise" is what the data says; "CW" is what a rider reads. */
        val short: String
            get() = canonicalDirection(directionName)

        /**
         * The name to show in a stop's service line.
         *
         * The ring lines are the numbers people use ("1", "2"); a short-turn
         * shuttle has no number worth reading, so its direction is its name
         * ("Uphill" / "Downhill"). Derived from the route name rather than a
         * hardcoded id, so a renamed or added line still reads correctly.
         */
        val label: String
            get() = Regex("(?:Line\\s*(\\d+)|(?:线路)?\\s*(\\d+)号线)", RegexOption.IGNORE_CASE)
                .find(routeName)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } } ?: short
    }

    /** One stop, with the display name the site composes from group + name. */
    data class Stop(
        val id: String,
        val name: String,
        val group: String,
        val latitude: Double,
        val longitude: Double,
        val serves: List<Serve> = emptyList(),
    ) {
        /** "Gate 2 (1)" — the group carries the landmark, the name the berth.
         *  Berth markers that only describe the stop's role on the loop
         *  ("Origin"/"Dest." at the terminal) are noise and are dropped. */
        val display: String
            get() {
                val berth = when (name.trim().lowercase()) {
                    "origin", "dest.", "dest", "起点", "终点", "始发站", "终点站" -> ""
                    else -> name.trim()
                }
                return when {
                    group.isEmpty() -> berth
                    berth.isEmpty() -> group
                    else -> "$group $berth"
                }
            }

        /** Straight-line metres to a point — good enough to rank stops. */
        fun metresTo(latitude: Double, longitude: Double): Int =
            haversine(this.latitude, this.longitude, latitude, longitude)
    }

    /**
     * One place on campus, with every berth that serves it.
     *
     * The stop list is a list of *berths* ("Hui Yuan Uphill", "Hui Yuan
     * Downhill", "Gate 1 (1)", "Gate 1 (2)"), which is the site's model but not
     * a rider's: they are going to 慧园, not to a berth. Two berths of one
     * landmark are two directions of the same place, so they collapse into one
     * row and the direction becomes a choice made after tapping in.
     */
    data class Landmark(
        val name: String,
        val berths: List<Stop>,
    ) {
        /** The merged service line: "1/2 · Uphill/Downhill". */
        val labels: List<String>
            get() {
                val serves = berths.flatMap { it.serves }.distinctBy { it.label }
                val ring = serves.map { it.label }.filter { it.toIntOrNull() != null }
                    .distinct().sortedBy { it.toIntOrNull() }
                val rest = serves.map { it.label }.filter { it.toIntOrNull() == null }
                    .distinct()
                    .sortedBy { label ->
                        DIRECTION_ORDER.indexOf(label).takeIf { it >= 0 } ?: Int.MAX_VALUE
                    }
                // One token per family — the ring lines read "1/2" and the
                // shuttles "Uphill/Downhill" — instead of one token per berth.
                return listOfNotNull(
                    ring.takeIf { it.isNotEmpty() }?.joinToString("/"),
                    rest.takeIf { it.isNotEmpty() }?.joinToString("/"),
                )
            }

            /** Every direction a bus can be taken in from here, in ride order. */
            val directions: List<Serve>
                get() = berths.flatMap { it.serves }
                    .distinctBy { it.directionId }
                    .sortedBy { DIRECTION_ORDER.indexOf(it.short).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }

        /** The berth that a given direction leaves from. */
        fun berthFor(directionId: String): Stop? =
            berths.firstOrNull { berth -> berth.serves.any { it.directionId == directionId } }

        fun nearestMetres(latitude: Double, longitude: Double): Int =
            berths.minOf { it.metresTo(latitude, longitude) }
    }

    /**
     * One expected arrival.
     *
     * A row is only meaningful when this stop is **not** the direction's final
     * stop — arrivals at the terminal itself are filtered out (the bus ends
     * here; there is nothing to wait for), same rule the site applies.
     *
     * [minutes] is null whenever the server has no live estimate. [plannedAt]
     * then carries the scheduled clock time ("14:25") when the source is the
     * timetable; a null with no time is a reason ([reason]).
     *
     * [metresAway] is how far the bus physically is from the stop right now —
     * the number that turns an ETA into a picture.
     *
     * [terminal] names where this direction ends — the honest replacement for
     * direction words like "Uphill/Downhill", which no rider can map to a
     * place.
     */
    data class Arrival(
        val routeName: String,
        val direction: String,
        val directionId: String,
        val terminal: String,
        val minutes: Int?,
        val plannedAt: String,
        val metresAway: Int?,
        val source: String,
        val reason: String,
        val color: String,
        val upcoming: List<String>,
        /** Joins this prediction to a physical bus in `/vehicles`. */
        val tripId: String = "",
        /** Stops between the bus and this stop, from the live vehicle's own index. */
        val stopsAway: Int? = null,
        /** The stop the bus is heading for right now, by name. */
        val nextBusStop: String = "",
    ) {
        /**
         * How far along the approach picture is, 0..1. Null when there is no
         * distance to show (timetable row or the API sent none) — the picture
         * then falls back to the ETA alone.
         */
        val approach: Float?
            get() {
                val metres = metresAway ?: return null
                if (metres < 0) return null
                // 1500 m is treated as "far end of the loop": buses arrive
                // from inside that radius, and clamping keeps one honest bar
                // for everything from around-the-corner to loop-away.
                return (1.0 - metres / 1500.0).coerceIn(0.0, 1.0).toFloat()
            }
    }

    /** A live bus: where it is, where it is going. */
    data class Vehicle(
        val routeName: String,
        val direction: String,
        val nextStop: String,
        val speedKph: Double?,
        val latitude: Double,
        val longitude: Double,
        val operating: Boolean,
        /** The scheduled run this bus is serving — the key `/arrivals` joins on. */
        val tripId: String = "",
        /** 1-based index of the stop it is heading for, along its direction. */
        val nextStopNum: Int? = null,
        val nextStopId: String = "",
    ) {
        val summary: String
            get() = buildList {
                add(listOf(routeName, direction).filter { it.isNotEmpty() }.joinToString(" · "))
                if (nextStop.isNotEmpty()) add("next: $nextStop")
                speedKph?.takeIf { it >= 1 }?.let { add("${it.toInt()} km/h") }
            }.filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** A line: its colour, when it runs, and the stop order of each direction. */
    data class Route(
        val id: String,
        val name: String,
        val color: String,
        val serviceTime: String,
        val status: String,
        val directions: List<Direction>,
    ) {
        val directionNames: List<String> get() = directions.map { it.name }
    }

    /** One direction of a line, with its stops in travel order. */
    data class Direction(
        val id: String,
        val name: String,
        val stopIds: List<String>,
    )

    data class Notice(val title: String, val body: String)

    fun stops(): List<Stop> = Cache.get("bus.stops.$languageTag", Cache.TTL_LIST) {
        val array = getArray("/stops")
        (0 until array.length()).mapNotNull { i ->
            val stop = array.optJSONObject(i) ?: return@mapNotNull null
            val id = stop.optString("id")
            if (id.isEmpty()) return@mapNotNull null
            // Each `directions[]` entry IS a berth's service: a route *and* the
            // direction it leaves in. The route name is on the entry, so the
            // routes() call this used to need is gone.
            val directions = stop.optJSONArray("directions") ?: JSONArray()
            val serves = (0 until directions.length()).mapNotNull berthLoop@{ d ->
                val serve = directions.optJSONObject(d) ?: return@berthLoop null
                val routeId = serve.optString("route_id")
                if (routeId.isEmpty()) return@berthLoop null
                Serve(
                    routeId = routeId,
                    routeName = localized(serve, "route_name"),
                    directionId = serve.optString("route_direction_id"),
                    directionName = localized(serve, "direction_name"),
                    color = serve.optString("route_color"),
                )
            }
            Stop(
                id = id,
                name = localized(stop, "name"),
                group = localized(stop, "group_name"),
                latitude = stop.optDouble("latitude", Double.NaN),
                longitude = stop.optDouble("longitude", Double.NaN),
                serves = serves,
            )
        }
    }

    /**
     * The stops collapsed to places.
     *
     * A stop with no group stands alone (it is already one place); otherwise
     * the berths sharing a group are one row.
     */
    fun landmarks(): List<Landmark> = Cache.get("bus.landmarks.$languageTag", Cache.TTL_LIST) {
        val grouped = LinkedHashMap<String, MutableList<Stop>>()
        for (stop in stops()) {
            grouped.getOrPut(stop.group.ifEmpty { stop.display }) { ArrayList() }.add(stop)
        }
        grouped.map { (name, berths) -> Landmark(name, berths) }
    }

    /** The [limit] landmarks closest to a point, nearest first. */
    fun nearestLandmarks(latitude: Double, longitude: Double, limit: Int = 3): List<Pair<Landmark, Int>> =
        landmarks()
            .filter { landmark -> landmark.berths.any { !it.latitude.isNaN() && !it.longitude.isNaN() } }
            .map { it to it.nearestMetres(latitude, longitude) }
            .sortedBy { it.second }
            .take(limit)

    /**
     * The [limit] stops closest to a point, nearest first.
     *
     * This is what makes the screen useful on the way out of a building: the
     * reader gets a stop near them and the bus that is about to reach it,
     * without knowing campus stop names.
     */
    fun nearestStops(latitude: Double, longitude: Double, limit: Int = 3): List<Pair<Stop, Int>> =
        stops()
            .filter { !it.latitude.isNaN() && !it.longitude.isNaN() }
            .map { it to it.metresTo(latitude, longitude) }
            .sortedBy { it.second }
            .take(limit)

    /**
     * Arrivals at one stop, soonest first.
     *
     * Arrivals whose direction *ends* at this stop are dropped — a terminating
     * bus is not something you wait for (same rule the site applies on its
     * stop pages). The sort is a belt-and-braces guard so a widget never shows
     * a later bus above the one about to leave.
     */
    fun arrivals(stopId: String): List<Arrival> =
        Cache.get("bus.arrivals.$languageTag.$stopId", Cache.TTL_LIVE) {
            val arrivals = getObject("/arrivals/$stopId").optJSONArray("arrivals") ?: JSONArray()
            val routesById = runCatching { routes() }.getOrDefault(emptyList()).associateBy { it.id }
            val stopsById = runCatching { stops() }.getOrDefault(emptyList()).associateBy { it.id }
            // The physical buses behind these predictions. `/vehicles` carries
            // the same trip id, and a bus's own stop index is what turns its
            // countdown into a place.
            val busesByTrip = runCatching { vehicles() }.getOrDefault(emptyList())
                .filter { it.tripId.isNotEmpty() }
                .associateBy { it.tripId }
            (0 until arrivals.length()).mapNotNull { i ->
                val item = arrivals.optJSONObject(i) ?: return@mapNotNull null
                val routeId = item.optString("route_id")
                val directionId = item.optString("route_direction_id")
                val direction = routesById[routeId]?.directions
                    ?.firstOrNull { it.id == directionId }
                // A bus whose last stop is this one is not an arrival to wait
                // for — it ends here.
                if (direction != null && direction.stopIds.lastOrNull() == stopId) {
                    return@mapNotNull null
                }
                val tripId = item.optString("trip_id")
                val bus = busesByTrip[tripId]
                // 1-based position of this stop along the direction, so it can
                // be subtracted from the bus's own 1-based position.
                val here = direction?.stopIds?.indexOf(stopId)?.takeIf { it >= 0 }?.plus(1)
                val busAt = bus?.nextStopNum
                Arrival(
                    routeName = localized(item, "route_name"),
                    direction = localized(item, "direction_name"),
                    directionId = directionId,
                    terminal = direction?.stopIds?.lastOrNull()
                        ?.let { stopsById[it]?.display }.orEmpty(),
                    minutes = if (item.has("eta_minutes") && !item.isNull("eta_minutes"))
                        item.optInt("eta_minutes") else null,
                    plannedAt = formatTime(item.optString("planned_arrival_at")),
                    metresAway = if (item.has("distance") && !item.isNull("distance"))
                        item.optInt("distance") else null,
                    source = item.optString("source"),
                    reason = item.optString("unavailable_reason"),
                    color = item.optString("route_color"),
                    upcoming = upcomingStops(direction, stopId, stopsById),
                    tripId = tripId,
                    stopsAway = if (here != null && busAt != null) here - busAt else null,
                    nextBusStop = bus?.nextStopId?.let { stopsById[it]?.display }
                        ?: bus?.nextStop.orEmpty(),
                )
            }.sortedBy { it.minutes ?: Int.MAX_VALUE }
        }

    /** ISO timestamp → "HH:mm", empty when absent or unparseable. */
    private fun formatTime(iso: String): String {
        if (iso.isEmpty()) return ""
        return runCatching {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US,
                ).parse(iso.take(19)))
        }.getOrDefault("")
    }

    /**
     * The next few stops after [stopId] along [direction], by name.
     *
     * No wrap-around: these are loops, and their stop list already ends at the
     * terminal, so wrapping would repeat the terminal stop and read as noise.
     */
    private fun upcomingStops(
        direction: Direction?,
        stopId: String,
        stopsById: Map<String, Stop>,
        count: Int = UPCOMING_STOPS,
    ): List<String> {
        val ids = direction?.stopIds ?: return emptyList()
        val at = ids.indexOf(stopId)
        if (at < 0) return emptyList()
        return ids.drop(at + 1).take(count).mapNotNull { stopsById[it]?.display }
    }

    fun routes(): List<Route> = Cache.get("bus.routes.$languageTag", Cache.TTL_LIST) {
        val array = getArray("/routes")
        (0 until array.length()).mapNotNull { i ->
            val route = array.optJSONObject(i) ?: return@mapNotNull null
            val directionsJson = route.optJSONArray("directions") ?: JSONArray()
            Route(
                id = route.optString("id"),
                name = localized(route, "name"),
                color = route.optString("color"),
                serviceTime = route.optString("service_time"),
                status = route.optString("operation_status"),
                directions = (0 until directionsJson.length()).mapNotNull directionLoop@{ d ->
                    val direction = directionsJson.optJSONObject(d) ?: return@directionLoop null
                    val stopArray = direction.optJSONArray("stops") ?: JSONArray()
                    Direction(
                        id = direction.optString("id"),
                        name = localized(direction, "name"),
                        // Travel order: this is what turns "Clockwise" into
                        // "next stops are Research Bldg 3 → North Dorms → …".
                        stopIds = (0 until stopArray.length()).mapNotNull { s ->
                            stopArray.optJSONObject(s)?.optString("id")?.takeIf { it.isNotEmpty() }
                        },
                    )
                },
            )
        }
    }

    /**
     * Buses currently reporting a position.
     *
     * One request per cache window: this is the "where is it right now" view,
     * so it rides the short TTL rather than the list TTL.
     */
    fun vehicles(): List<Vehicle> = Cache.get("bus.vehicles.$languageTag", Cache.TTL_LIVE) {
        val routesById = routes().associateBy { it.id }
        val array = getArray("/vehicles")
        (0 until array.length()).mapNotNull { i ->
            val vehicle = array.optJSONObject(i) ?: return@mapNotNull null
            val route = routesById[vehicle.optString("route_id")]
            Vehicle(
                routeName = route?.name ?: localized(vehicle, "display_name"),
                direction = route?.directionNames?.firstOrNull { name ->
                    name == vehicle.optString("upstream_direction")
                } ?: "",
                nextStop = localized(vehicle, "next_stop_name").trim(),
                speedKph = if (vehicle.has("speed") && !vehicle.isNull("speed"))
                    vehicle.optDouble("speed", 0.0) else null,
                latitude = vehicle.optDouble("latitude", Double.NaN),
                longitude = vehicle.optDouble("longitude", Double.NaN),
                operating = vehicle.optBoolean("operating", true),
                tripId = vehicle.optString("trip_id").trim(),
                nextStopNum = vehicle.optJSONObject("current_position")?.let { position ->
                    if (position.has("next_stop_num") && !position.isNull("next_stop_num"))
                        position.optInt("next_stop_num") else null
                },
                nextStopId = vehicle.optJSONObject("current_position")
                    ?.optString("next_stop_id").orEmpty().trim(),
            )
        }
    }

    fun notices(): List<Notice> = Cache.get("bus.notices.$languageTag", Cache.TTL_LIST) {
        val array = getArray("/notices")
        (0 until array.length()).mapNotNull { i ->
            val notice = array.optJSONObject(i) ?: return@mapNotNull null
            Notice(
                title = localized(notice, "title"),
                body = localized(notice, "body_markdown").lineSequence()
                    .filter { it.isNotBlank() }.take(6).joinToString(" ")
                    .take(240),
            )
        }
    }

    // -- Transport ------------------------------------------------------------

    private fun getArray(path: String): JSONArray = try {
        JSONArray(get(path))
    } catch (_: Exception) {
        throw ApiException("Bus API returned a non-list body")
    }

    private fun getObject(path: String): JSONObject = try {
        JSONObject(get(path))
    } catch (_: Exception) {
        throw ApiException("Bus API returned a non-object body")
    }

    private fun get(path: String): String {
        val request = Request.Builder()
            .url(BASE + path)
            .get()
            .header("User-Agent", CasLogin.UA)
            .header("Accept", "application/json")
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.code >= 400) {
                    throw ApiException("Bus API answered HTTP ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw ApiException(e.message ?: "network error")
        }
    }

    companion object {
        /** The production base the site itself resolves for a built page. */
        const val BASE = "https://buseta.sustcra.com"

        /** How many stops ahead a row spells out before trailing off. */
        const val UPCOMING_STOPS = 3

        /** Direction words as a rider reads them, not as the data spells them. */
        const val CW = "CW"
        const val CCW = "CCW"
        const val UPHILL = "Uphill"
        const val DOWNHILL = "Downhill"

        /** The order a direction chooser lists things in. */
        val DIRECTION_ORDER = listOf(CW, CCW, UPHILL, DOWNHILL)

        /** Metres treated as the far end of an approach bar. */
        const val APPROACH_RANGE_M = 1500

        private fun canonicalDirection(name: String): String = when {
            name.equals("clockwise", ignoreCase = true) || name.contains("顺时针") -> CW
            name.equals("counter-clockwise", ignoreCase = true) ||
                name.equals("counterclockwise", ignoreCase = true) || name.contains("逆时针") -> CCW
            name.equals("uphill", ignoreCase = true) || name.contains("上坡") -> UPHILL
            name.equals("downhill", ignoreCase = true) || name.contains("下坡") -> DOWNHILL
            else -> name
        }

        /** Metres between two WGS-84 points (equirectangular is exact enough). */
        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Int {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return (2 * r * asin(min(1.0, sqrt(a)))).toInt()
        }
    }
}
