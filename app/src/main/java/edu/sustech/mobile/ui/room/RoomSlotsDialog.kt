package edu.sustech.mobile.ui.room

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.library.room.RoomApi
import edu.sustech.mobile.library.room.RoomInfo
import edu.sustech.mobile.library.room.RoomSlot
import edu.sustech.mobile.library.room.RoomSlotState
import edu.sustech.mobile.ui.SimpleAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A real per-room calendar, matching the official research-space page. */
class RoomSlotsDialog : DialogFragment() {

    private lateinit var room: RoomInfo
    private lateinit var days: List<Date>
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var note: TextView
    private lateinit var dates: ChipGroup
    private lateinit var adapter: SimpleAdapter<RoomSlot>
    private var selectedDay: Date = daysFallback()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        room = RoomInfo(
            devId = requireArguments().getInt(ARG_DEV),
            name = requireArguments().getString(ARG_NAME).orEmpty(),
            lab = requireArguments().getString(ARG_LAB).orEmpty(),
            people = requireArguments().getString(ARG_PEOPLE).orEmpty(),
            needsMembers = requireArguments().getBoolean(ARG_NEEDS),
            minMinutes = requireArguments().getInt(ARG_MIN),
            free = requireArguments().getBoolean(ARG_FREE),
            lending = requireArguments().getBoolean(ARG_LENDING),
        )
        val view = layoutInflater.inflate(R.layout.dialog_room_slots, null)
        view.findViewById<TextView>(R.id.room_slots_room).text = listOf(
            room.name,
            room.lab,
            room.people,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        note = view.findViewById(R.id.room_slots_note)
        dates = view.findViewById(R.id.room_slots_dates)
        progress = view.findViewById(R.id.room_slots_progress)
        empty = view.findViewById(R.id.room_slots_empty)
        list = view.findViewById(R.id.room_slots_list)
        list.layoutManager = GridLayoutManager(requireContext(), 3)
        adapter = SimpleAdapter(R.layout.item_booking_slot) { row, slot, _ ->
            val card = row as? MaterialCardView
            val color = when (slot.state) {
                RoomSlotState.AVAILABLE -> R.color.booking_slot_available_bg
                RoomSlotState.RESERVED -> R.color.booking_slot_reserved_bg
                RoomSlotState.IN_USE -> R.color.booking_slot_in_use_bg
                RoomSlotState.CLOSED -> R.color.booking_slot_unavailable_bg
            }
            val stroke = when (slot.state) {
                RoomSlotState.AVAILABLE -> R.color.booking_slot_available_stroke
                RoomSlotState.RESERVED -> R.color.booking_slot_reserved_stroke
                RoomSlotState.IN_USE -> R.color.booking_slot_in_use_stroke
                RoomSlotState.CLOSED -> R.color.booking_slot_unavailable_stroke
            }
            card?.setCardBackgroundColor(requireContext().getColor(color))
            card?.strokeColor = requireContext().getColor(stroke)
            card?.strokeWidth = 1
            row.findViewById<TextView>(R.id.booking_slot_time).text = "${slot.start}–${slot.end}"
            row.findViewById<TextView>(R.id.booking_slot_status).apply {
                text = getString(
                    when (slot.state) {
                        RoomSlotState.AVAILABLE -> R.string.rooms_slots_available
                        RoomSlotState.RESERVED -> R.string.rooms_slots_reserved
                        RoomSlotState.IN_USE -> R.string.rooms_slots_in_use
                        RoomSlotState.CLOSED -> R.string.rooms_slots_closed
                    },
                )
                setTextColor(requireContext().getColor(statusColor(slot.state)))
            }
            row.findViewById<TextView>(R.id.booking_slot_reason).apply {
                text = slot.title
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
            row.isClickable = slot.state == RoomSlotState.AVAILABLE
            row.setOnClickListener {
                if (slot.state == RoomSlotState.AVAILABLE) {
                    BookDialog.newInstance(room, selectedDay, slot.start)
                        .show(parentFragmentManager, "book")
                }
            }
        }
        list.adapter = adapter
        days = RoomApi.bookableDays()
        buildDates()
        loadDay(days.firstOrNull())

        return androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.rooms_slots_title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun statusColor(state: RoomSlotState): Int = when (state) {
        RoomSlotState.AVAILABLE -> R.color.ok
        RoomSlotState.RESERVED -> R.color.warn
        RoomSlotState.IN_USE -> R.color.bad
        RoomSlotState.CLOSED -> R.color.ink_muted
    }

    private fun daysFallback(): Date = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.time

    private fun buildDates() {
        dates.removeAllViews()
        days.forEachIndexed { index, day ->
            val chip = Chip(requireContext()).apply {
                id = View.generateViewId()
                text = dateLabel(day, index)
                isCheckable = true
                setOnClickListener { loadDay(day) }
            }
            dates.addView(chip)
        }
        dates.getChildAt(0)?.let { dates.check(it.id) }
    }

    private fun loadDay(day: Date?) {
        if (day == null) return
        selectedDay = day
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        adapter.submit(emptyList())
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { App.rooms.roomSchedule(room.devId, day, room.labId) }
            }
            if (!isAdded) return@launch
            progress.visibility = View.GONE
            result.onSuccess { slots ->
                note.text = getString(R.string.rooms_slots_note)
                adapter.submit(slots)
                empty.text = getString(R.string.rooms_slots_empty)
                empty.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                note.text = getString(R.string.rooms_slots_error)
                empty.text = it.friendly(requireContext())
                empty.visibility = View.VISIBLE
            }
        }
    }

    private fun dateLabel(day: Date, index: Int): String = when (index) {
        0 -> getString(R.string.rooms_slots_today)
        1 -> getString(R.string.rooms_slots_tomorrow)
        else -> DAY.format(day)
    }

    companion object {
        private val DAY = SimpleDateFormat("M月d日 E", Locale.CHINA)
        private const val ARG_DEV = "dev"
        private const val ARG_NAME = "name"
        private const val ARG_LAB = "lab"
        private const val ARG_PEOPLE = "people"
        private const val ARG_NEEDS = "needs"
        private const val ARG_MIN = "min"
        private const val ARG_FREE = "free"
        private const val ARG_LENDING = "lending"

        fun newInstance(room: RoomInfo) = RoomSlotsDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_DEV, room.devId)
                putString(ARG_NAME, room.name)
                putString(ARG_LAB, room.lab)
                putString(ARG_PEOPLE, room.people)
                putBoolean(ARG_NEEDS, room.needsMembers)
                putInt(ARG_MIN, room.minMinutes)
                putBoolean(ARG_FREE, room.free)
                putBoolean(ARG_LENDING, room.lending)
            }
        }
    }
}
