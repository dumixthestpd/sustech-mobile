package edu.sustech.mobile.ui.transit

import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.transit.BusApi
import edu.sustech.mobile.transit.LocationProbe
import edu.sustech.mobile.transit.TransitText
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.ServicePage
import edu.sustech.mobile.ui.TabbedServiceFragment
import edu.sustech.mobile.ui.hostShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The place whose buses are open, and the direction chosen there.
 *
 * Shared between the two tabs, which are separate fragments and never see each
 * other's arguments.
 */
object TransitSelection {
    @Volatile var landmark: BusApi.Landmark? = null
    @Volatile var directionId: String = ""
    @Volatile var metresAway: Int? = null

    /** The direction's own word — "CW", "Uphill" — for chrome that names it. */
    val directionShort: String
        get() = landmark?.directions?.firstOrNull { it.directionId == directionId }?.short.orEmpty()
}

/**
 * `parentFragment` is null for pages swapped in with `replace()` — the shell
 * has to be found through the activity's FragmentManager.
 */
private fun Fragment.shell(): TransitFragment? = hostShell() as? TransitFragment

/**
 * Campus shuttle, as two levels and nothing more:
 *
 * 1. **Stops** — every *place*, closest first. The API models berths ("Hui Yuan
 *    Uphill", "Gate 1 (2)"); a rider is going to 慧园, so the berths of one
 *    landmark are one row and the service line merges them ("1/2 ·
 *    Uphill/Downhill") instead of listing four route names.
 * 2. Tap a place → **Buses**: pick a direction (CW / CCW / Uphill / Downhill)
 *    and get that direction's buses on the way.
 */
class TransitFragment : TabbedServiceFragment(R.layout.fragment_tabs) {

    override fun pages(): List<ServicePage> = listOf(
        ServicePage(R.string.transit_stops) { TransitStopsFragment() },
        ServicePage(R.string.transit_buses) { TransitBusesFragment() },
    )

    override fun onServiceViewReady(view: View) {
        banner(getString(R.string.transit_credit), null)
    }

    /**
     * Called by a stop row: open this place's buses.
     *
     * The direction starts empty so the Buses tab asks — that is the whole
     * point of merging the berths away. A place with only one direction has
     * nothing to ask, so it is chosen for the reader.
     */
    fun open(landmark: BusApi.Landmark, metres: Int?) {
        TransitSelection.landmark = landmark
        TransitSelection.metresAway = metres
        TransitSelection.directionId = landmark.directions.singleOrNull()?.directionId.orEmpty()
        selectTab(BUSES)
    }

    /** The direction chips' handler. */
    fun choose(directionId: String) {
        TransitSelection.directionId = directionId
    }

    /** The Buses tab names the place it is showing, and how far it is. */
    fun showPlace(metres: Int?) {
        val landmark = TransitSelection.landmark ?: return
        val away = metres?.let { TransitText.distance(requireContext(), it) }
        banner(
            listOfNotNull(
                landmark.name,
                TransitSelection.directionShort.takeIf { it.isNotEmpty() }
                    ?.let { TransitText.direction(requireContext(), it) },
                away,
                getString(R.string.transit_credit),
            ).joinToString(" · "),
            null,
        )
    }

    private companion object {
        const val BUSES = 1
    }
}

/**
 * Level 1 — the place list, closest first.
 *
 * A row is a picture: a badge with how many directions leave from here, the
 * landmark, the merged service line, and how far it is. Tapping opens its
 * buses.
 */
class TransitStopsFragment : ListFragment<TransitStopsFragment.Row>(R.layout.fragment_list) {

    data class Row(
        val landmark: BusApi.Landmark,
        val metres: Int,
    )

    private var pendingPermission = false

    private val requestLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) load(force = true)
    }

    override fun cachePrefix() = "bus.landmarks"

    override fun rowLayout() = R.layout.item_transit_stop

    override fun emptyText() = getString(R.string.transit_no_stops)

    override fun errorText(error: Throwable): String =
        context?.getString(R.string.transit_load_error) ?: ""

    override fun onReady(view: View) {
        if (!LocationProbe.granted(requireContext()) && !pendingPermission) {
            pendingPermission = true
            requestLocation.launch(LocationProbe.permissions)
        }
    }

    override suspend fun fetch(): List<Row> {
        val here = LocationProbe.lastKnown(requireContext())
        val places = App.bus.landmarks()
        return if (here != null) {
            places.map { Row(it, it.nearestMetres(here.latitude, here.longitude)) }
                .sortedBy { it.metres }
        } else {
            places.sortedBy { it.name }.map { Row(it, -1) }
        }
    }

    override fun bindRow(view: View, item: Row, position: Int) {
        view.findViewById<TextView>(R.id.transit_stop_badge).text =
            item.landmark.directions.size.takeIf { it > 0 }?.toString() ?: "·"
        view.findViewById<TextView>(R.id.transit_stop_name).text = item.landmark.name
        view.findViewById<TextView>(R.id.transit_stop_sub).text =
            TransitText.serviceLines(requireContext(), item.landmark.labels)
        view.findViewById<TextView>(R.id.transit_stop_away).text =
            if (item.metres >= 0) TransitText.distance(requireContext(), item.metres) else ""
        view.setOnClickListener {
            shell()?.open(item.landmark, item.metres.takeIf { it >= 0 })
        }
    }

}

/**
 * Level 2 — buses on the way for one place in one direction.
 *
 * The direction chooser sits above the list and stays there, so switching
 * direction never means going back. Refreshes every 15 s while open.
 */
class TransitBusesFragment : ListFragment<BusApi.Arrival>(R.layout.fragment_transit_buses) {

    private var ticker: Job? = null

    override fun cachePrefix() = "bus.arrivals."

    override fun rowLayout() = R.layout.item_transit_arrival

    override fun emptyText(): String = if (TransitSelection.directionId.isEmpty()) {
        getString(R.string.transit_choose_direction)
    } else {
        getString(R.string.transit_no_arrivals)
    }

    override fun errorText(error: Throwable): String =
        context?.getString(R.string.transit_load_error) ?: ""

    override fun onReady(view: View) {
        if (TransitSelection.landmark != null) {
            // Arrived by tapping a stop: the chooser is the first thing to draw.
            buildDirections(view)
            return
        }
        // Opened straight from the tabs: start on the nearest place.
        runIo(
            block = {
                val here = LocationProbe.lastKnown(requireContext())
                if (here != null) App.bus.nearestLandmarks(here.latitude, here.longitude, 1)
                else App.bus.landmarks().take(1).map { it to -1 }
            },
            onOk = { ranked ->
                ranked.firstOrNull()?.let { (landmark, metres) ->
                    TransitSelection.landmark = landmark
                    TransitSelection.metresAway = metres.takeIf { it >= 0 }
                    TransitSelection.directionId =
                        landmark.directions.singleOrNull()?.directionId.orEmpty()
                    buildDirections(view)
                    load(force = true)
                }
            },
        )
    }

    override fun onLoaded(rows: List<BusApi.Arrival>) {
        shell()?.showPlace(TransitSelection.metresAway)
    }

    /** The direction chips: one per direction that leaves from this place. */
    private fun buildDirections(view: View) {
        val group = view.findViewById<ChipGroup>(R.id.transit_directions) ?: return
        val landmark = TransitSelection.landmark ?: return
        group.removeAllViews()
        val directions = landmark.directions
        // One direction is not a question, and no direction is not a chooser.
        group.visibility = if (directions.size > 1) View.VISIBLE else View.GONE
        if (directions.size <= 1) return
        for (serve in directions) {
            val chip = Chip(requireContext())
            chip.text = TransitText.direction(requireContext(), serve.short)
            chip.isCheckable = true
            chip.isChecked = serve.directionId == TransitSelection.directionId
            chip.id = View.generateViewId()
            chip.setOnClickListener {
                TransitSelection.directionId = serve.directionId
                load(force = true)
            }
            group.addView(chip)
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { buildDirections(it) }
        ticker = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                load(force = true)
            }
        }
    }

    override fun onPause() {
        ticker?.cancel()
        ticker = null
        super.onPause()
    }

    override suspend fun fetch(): List<BusApi.Arrival> {
        val landmark = TransitSelection.landmark ?: return emptyList()
        val directionId = TransitSelection.directionId
        if (directionId.isEmpty()) return emptyList()
        val berth = landmark.berthFor(directionId) ?: return emptyList()
        // One berth can carry several directions; only the chosen one is shown.
        return App.bus.arrivals(berth.id).filter { it.directionId == directionId }
    }

    override fun bindRow(view: View, item: BusApi.Arrival, position: Int) {
        val tint = parseColor(item.color)
        val dot = view.findViewById<View>(R.id.transit_line_dot)
        val bar = view.findViewById<android.widget.ProgressBar>(R.id.transit_approach)

        view.findViewById<TextView>(R.id.transit_line_name).text = item.routeName
        view.findViewById<TextView>(R.id.transit_eta).text = TransitText.eta(requireContext(), item)

        // Where this direction ends — "to Xinyuan Terminal" answers the
        // "which way does it go" question better than any direction word.
        // The first stop after this one grounds it further.
        val goingTo = item.upcoming.firstOrNull()
        view.findViewById<TextView>(R.id.transit_direction).text =
            listOfNotNull(
                item.terminal.takeIf { it.isNotEmpty() }
                    ?.let { getString(R.string.transit_to, it) },
                goingTo?.let { getString(R.string.transit_via, it) },
            ).joinToString(" ")

        // The approach picture: full bar = at the stop. Timetable rows have
        // no distance, so the bar hides and the sub-line carries the meaning.
        val approach = item.approach
        if (approach != null) {
            bar.visibility = View.VISIBLE
            bar.progress = (approach * 100).toInt()
            tintBar(bar, tint)
        } else {
            bar.visibility = View.GONE
        }

        // Where the bus actually is, in stops: a countdown says how long, this
        // says where — and "8 stops to go" is what a rider decides on when the
        // number and the bar disagree.
        val busPosition = view.findViewById<TextView>(R.id.transit_position)
        val position = TransitText.position(requireContext(), item)
        busPosition.text = position
        busPosition.visibility = if (position.isEmpty()) View.GONE else View.VISIBLE

        view.findViewById<TextView>(R.id.transit_sub).text = listOfNotNull(
            item.metresAway?.takeIf { it >= 0 }?.let { TransitText.distance(requireContext(), it) },
            TransitText.source(requireContext(), item).takeIf { it.isNotEmpty() },
        ).joinToString(" · ")

        dot.background.setTint(tint)
    }

    private fun parseColor(hex: String): Int =
        runCatching { Color.parseColor(hex.ifEmpty { "#00AB5B" }) }
            .getOrDefault(Color.parseColor("#00AB5B"))

    private fun tintBar(bar: android.widget.ProgressBar, color: Int) {
        bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
