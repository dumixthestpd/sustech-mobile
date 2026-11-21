package edu.sustech.mobile.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.service.Services
import edu.sustech.mobile.transit.BusApi
import edu.sustech.mobile.transit.LocationProbe
import edu.sustech.mobile.transit.TransitText
import edu.sustech.mobile.ui.transit.TransitSelection

/** A summary over the existing bus contract; no invented nearest stop without a location. */
class HomeBusCard(private val fragment: Fragment, private val root: View) {
    private val context get() = root.context
    private var places = emptyList<BusApi.Landmark>()
    private var selected: BusApi.Landmark? = null
    private var direction = ""
    private var generation = 0
    private var loading = false
    private var pinned = false
    private val place get() = root.findViewById<TextView>(R.id.home_bus_place)
    private val arrival get() = root.findViewById<TextView>(R.id.home_bus_arrival)
    private val source get() = root.findViewById<TextView>(R.id.home_bus_source)

    init {
        root.findViewById<View>(R.id.home_bus_choose).setOnClickListener {
            if (places.isEmpty()) refresh() else AlertDialog.Builder(context).setTitle(R.string.home_bus_choose)
                .setItems((listOf(context.getString(R.string.widget_stop_auto)) + places.map { it.name }).toTypedArray()) { _, index ->
                    if (index == 0) {
                        HomePreferences.busSelection(context, "", ""); selected = null
                        generation++; loading = false; refresh()
                    } else choose(places[index - 1], "")
                }.show()
        }
        root.findViewById<View>(R.id.home_bus_open).setOnClickListener {
            selected?.let { TransitSelection.landmark = it; TransitSelection.directionId = direction }
            fragment.startActivity(ServiceActivity.intent(context, Services.transit).putExtra("transit_buses", selected != null))
        }
    }

    fun refresh() {
        if (loading) return
        loading = true
        val request = ++generation
        arrival.setText(R.string.empty_loading); source.text = ""
        fragment.viewLifecycleOwner.runIo(block = {
            val all = App.bus.landmarks()
            val saved = HomePreferences.busSelection(context)
            val here = LocationProbe.lastKnown(context)
            val picked = all.firstOrNull { p -> p.berths.any { it.id == saved.first } }
                ?: here?.let { all.minByOrNull { p -> p.nearestMetres(it.latitude, it.longitude) } }
            Triple(all, picked, saved.second)
        }, onOk = { (all, picked, savedDirection) ->
            if (request != generation) return@runIo
            places = all
            if (picked == null) {
                loading = false
                place.text = ""; arrival.setText(R.string.home_bus_location_hint)
                root.findViewById<ChipGroup>(R.id.home_bus_directions).removeAllViews()
            } else choose(picked, savedDirection, savedStop = HomePreferences.busSelection(context).first.isNotBlank())
        }, onErr = { if (request == generation) { loading = false; arrival.setText(R.string.transit_load_error) } })
    }

    private fun choose(landmark: BusApi.Landmark, preferred: String, savedStop: Boolean = true) {
        loading = true
        pinned = savedStop
        selected = landmark
        direction = preferred.takeIf { key -> landmark.directions.any { it.directionId == key } }
            ?: landmark.directions.firstOrNull()?.directionId.orEmpty()
        val stop = landmark.berths.firstOrNull()?.id.orEmpty()
        HomePreferences.busSelection(context, if (pinned) stop else "", direction)
        place.text = landmark.name
        val group = root.findViewById<ChipGroup>(R.id.home_bus_directions)
        group.removeAllViews()
        landmark.directions.forEach { serve ->
            group.addView(Chip(context).apply {
                text = TransitText.direction(context, serve.short); tag = serve.directionId
                isCheckable = true; isChecked = direction == serve.directionId
                setOnClickListener { choose(landmark, serve.directionId, pinned) }
            })
        }
        val request = ++generation
        arrival.setText(R.string.empty_loading); source.text = ""
        val berth = landmark.berthFor(direction)
        val requestedDirection = direction
        fragment.viewLifecycleOwner.runIo(block = {
            val stops = App.bus.stops().associateBy { it.id }
            val path = App.bus.routes().flatMap { it.directions }.firstOrNull { it.id == requestedDirection }?.stopIds.orEmpty()
            val index = path.indexOf(berth?.id)
            val next = if (index >= 0) path.getOrNull(index + 1)?.let { stops[it]?.display } else null
            val rows = berth?.let { App.bus.arrivals(it.id).filter { row -> row.directionId == requestedDirection } }.orEmpty()
            next to rows.firstOrNull()
        }, onOk = { (next, row) ->
            if (request != generation) return@runIo
            loading = false
            arrival.text = row?.let { TransitText.eta(context, it) } ?: context.getString(R.string.transit_no_arrivals)
            source.text = listOfNotNull(next?.let { context.getString(R.string.transit_direction_next, it) },
                row?.let { TransitText.source(context, it) }, context.getString(R.string.transit_credit)).filter { it.isNotBlank() }.joinToString("\n")
        }, onErr = { if (request == generation) { loading = false; arrival.setText(R.string.transit_load_error) } })
    }
}
