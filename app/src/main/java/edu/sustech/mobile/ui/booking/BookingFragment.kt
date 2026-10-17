package edu.sustech.mobile.ui.booking

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import edu.sustech.mobile.R
import edu.sustech.mobile.booking.BookingMeeting
import edu.sustech.mobile.booking.BookingRoom
import edu.sustech.mobile.booking.BookingSlot
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.friendly
import edu.sustech.mobile.ui.ListFragment
import edu.sustech.mobile.ui.ServicePage
import edu.sustech.mobile.ui.SimpleAdapter
import edu.sustech.mobile.ui.TabbedServiceFragment
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Venue booking, read natively.
 *
 * Two questions: which rooms can I book, and what have I already booked. Both
 * are plain JSON reads behind a CAS ticket → bearer-token handshake, so neither
 * needs the browser page the app used to open. Booking and cancelling a room
 * change a real calendar and stay on the official page until they can be
 * previewed and read back here.
 */
class BookingFragment : TabbedServiceFragment(R.layout.fragment_tabs) {

    override fun pages(): List<ServicePage> = listOf(
        ServicePage(R.string.booking_rooms) { BookingRoomsFragment() },
        ServicePage(R.string.booking_mine) { BookingMeetingsFragment() },
    )

    override fun onServiceViewReady(view: View) {
        banner(getString(R.string.booking_online_note), null)
    }
}

/** Every bookable venue, searchable by name, type or building. */
class BookingRoomsFragment : ListFragment<BookingRoom>(R.layout.fragment_booking_rooms) {

    @Volatile
    private var query = ""
    private var all = emptyList<BookingRoom>()
    private var selectedDate = LocalDate.now()
    private var selectedFilter = FILTER_ALL
    private lateinit var summary: TextView
    private lateinit var date: TextView

    override fun rowLayout() = R.layout.item_booking_room

    override fun cachePrefix() = "booking.rooms."

    override fun emptyText(): String = if (query.isBlank()) {
        getString(R.string.booking_no_rooms)
    } else {
        getString(R.string.booking_no_rooms_match, query)
    }

    override fun errorText(error: Throwable): String = when {
        error is ApiException && error.httpStatus == 404 ->
            getString(R.string.booking_endpoint_not_found)
        error is ApiException && error.signInRequired ->
            getString(R.string.booking_sign_in_required)
        else -> super.errorText(error)
    }

    override fun onReady(view: View) {
        summary = view.findViewById(R.id.booking_summary)
        date = view.findViewById(R.id.booking_date)
        date.text = selectedDate.toString()
        date.setOnClickListener { pickDate() }
        val search = view.findViewById<EditText>(R.id.booking_search)
        query = search.text.toString()
        search.setOnEditorActionListener { field, action, _ ->
            val isSearch = action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_NULL
            if (isSearch) {
                query = field.text.toString()
                load(force = true)
            }
            isSearch
        }
        val filters = view.findViewById<com.google.android.material.chip.ChipGroup>(R.id.booking_filters)
        listOf(
            R.string.booking_filter_all to FILTER_ALL,
            R.string.booking_filter_available to FILTER_AVAILABLE,
            R.string.booking_filter_unavailable to FILTER_UNAVAILABLE,
        ).forEach { (label, value) ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                id = View.generateViewId()
                text = getString(label)
                isCheckable = true
                tag = value
                setOnCheckedChangeListener { _, checked -> if (checked) { selectedFilter = value; apply() } }
            }
            filters.addView(chip)
        }
        filters.check(filters.getChildAt(0).id)
    }

    override suspend fun fetch(): List<BookingRoom> = App.booking.rooms(keyword = query)

    override fun onLoaded(rows: List<BookingRoom>) {
        all = rows
        apply()
    }

    private fun apply() {
        if (!isAdded) return
        val shown = all.filter { room ->
            when (selectedFilter) {
                FILTER_AVAILABLE -> room.available
                FILTER_UNAVAILABLE -> !room.available
                else -> true
            }
        }
        adapter.submit(shown)
        showEmpty(shown.isEmpty(), emptyText())
        summary.text = getString(R.string.booking_summary, all.count { it.available }, all.size, selectedDate.toString())
    }

    override fun bindRow(view: View, item: BookingRoom, position: Int) {
        view.findViewById<TextView>(R.id.booking_room_name).text = item.name.ifBlank { item.id }

        view.findViewById<TextView>(R.id.booking_room_where).text = listOf(
            item.type,
            item.location,
            if (item.capacity > 0) getString(R.string.booking_capacity, item.capacity) else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")

        val note = view.findViewById<TextView>(R.id.booking_room_note)
        note.text = when {
            !item.available -> getString(R.string.booking_room_unavailable)
            item.bookStart.isNotBlank() && item.bookEnd.isNotBlank() ->
                getString(R.string.booking_window, item.bookStart.take(5), item.bookEnd.take(5))
            item.approvalRequired -> getString(R.string.booking_needs_approval)
            item.daysAhead > 0 -> getString(R.string.booking_bookable_days, item.daysAhead)
            else -> ""
        }
        note.visibility = if (note.text.isBlank()) View.GONE else View.VISIBLE
        view.findViewById<TextView>(R.id.booking_room_slots).text = getString(R.string.booking_view_slots)
        view.setOnClickListener {
            BookingSlotsDialog.newInstance(item, selectedDate.toString())
                .show(childFragmentManager, "booking-slots")
        }
    }

    private fun pickDate() {
        DatePickerDialog(requireContext(), { _, year, month, day ->
            selectedDate = LocalDate.of(year, month + 1, day)
            date.text = selectedDate.toString()
            apply()
        }, selectedDate.year, selectedDate.monthValue - 1, selectedDate.dayOfMonth).show()
    }

    private companion object {
        const val FILTER_ALL = "all"
        const val FILTER_AVAILABLE = "available"
        const val FILTER_UNAVAILABLE = "unavailable"
    }
}

/** The reservations this account holds. */
class BookingMeetingsFragment : ListFragment<BookingMeeting>(R.layout.fragment_booking_meetings) {

    override fun rowLayout() = R.layout.item_booking_meeting

    override fun cachePrefix() = "booking.meetings."

    override fun emptyText() = getString(R.string.booking_no_meetings)

    override suspend fun fetch(): List<BookingMeeting> = App.booking.meetings()

    override fun bindRow(view: View, item: BookingMeeting, position: Int) {
        view.findViewById<TextView>(R.id.booking_meeting_title).text =
            item.title.ifBlank { item.roomName.ifBlank { item.id } }
        view.findViewById<TextView>(R.id.booking_meeting_where).text = item.roomName

        val hours = listOf(item.startAt, item.endAt).filter { it.isNotBlank() }.joinToString(" → ")
        val whenLine = listOf(hours, item.status.takeIf { it.isNotBlank() && !it.all(Char::isDigit) })
            .filterNotNull()
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val line = view.findViewById<TextView>(R.id.booking_meeting_when)
        line.text = whenLine
        line.visibility = if (whenLine.isBlank()) View.GONE else View.VISIBLE
    }
}

/** Read-only slot map for one venue and date. */
class BookingSlotsDialog : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val room = BookingRoom(
            requireArguments().getString(ARG_ID).orEmpty(),
            requireArguments().getString(ARG_NAME).orEmpty(),
            requireArguments().getString(ARG_TYPE).orEmpty(),
            requireArguments().getInt(ARG_CAPACITY),
            requireArguments().getString(ARG_LOCATION).orEmpty(),
            requireArguments().getBoolean(ARG_AVAILABLE),
            requireArguments().getBoolean(ARG_APPROVAL),
            requireArguments().getInt(ARG_DAYS),
            requireArguments().getString(ARG_START).orEmpty(),
            requireArguments().getString(ARG_END).orEmpty(),
        )
        val date = requireArguments().getString(ARG_DATE).orEmpty()
        val view = layoutInflater.inflate(R.layout.dialog_booking_slots, null)
        val list = view.findViewById<RecyclerView>(R.id.booking_slots_list)
        val empty = view.findViewById<TextView>(R.id.booking_slots_empty)
        val notice = view.findViewById<TextView>(R.id.booking_slots_notice)
        val progress = view.findViewById<ProgressBar>(R.id.booking_slots_progress)
        val adapter = SimpleAdapter<BookingSlot>(R.layout.item_booking_slot) { row, slot, _ ->
            val card = row as? MaterialCardView
            card?.setCardBackgroundColor(
                requireContext().getColor(
                    if (slot.available) R.color.booking_slot_available_bg
                    else R.color.booking_slot_unavailable_bg,
                ),
            )
            card?.strokeColor = requireContext().getColor(
                if (slot.available) R.color.booking_slot_available_stroke
                else R.color.booking_slot_unavailable_stroke,
            )
            card?.strokeWidth = 1
            row.findViewById<TextView>(R.id.booking_slot_time).text = "${slot.start}–${slot.end}"
            val status = row.findViewById<TextView>(R.id.booking_slot_status)
            status.text = getString(if (slot.available) R.string.booking_slot_available else R.string.booking_slot_unavailable)
            status.setTextColor(requireContext().getColor(if (slot.available) R.color.ok else R.color.ink_muted))
            row.contentDescription = "${slot.start}–${slot.end}, ${status.text}"
            row.findViewById<TextView>(R.id.booking_slot_reason).apply {
                text = slot.meetingTitle
                visibility = if (text.isBlank()) View.GONE else View.VISIBLE
            }
        }
        list.adapter = adapter
        list.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 3)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(room.name)
            .setView(view)
            .create()
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { App.booking.roomSlots(room, date) } }
            if (!isAdded) return@launch
            progress.visibility = View.GONE
            result.onSuccess { slots ->
                adapter.submit(slots)
                notice.visibility = if (slots.any { !it.live }) View.VISIBLE else View.GONE
                empty.visibility = if (slots.isEmpty()) View.VISIBLE else View.GONE
            }.onFailure {
                empty.text = if (it is ApiException && it.httpStatus == 404) {
                    getString(R.string.booking_slots_unavailable)
                } else {
                    it.friendly(requireContext())
                }
                empty.visibility = View.VISIBLE
            }
        }
        return dialog
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"
        private const val ARG_TYPE = "type"
        private const val ARG_CAPACITY = "capacity"
        private const val ARG_LOCATION = "location"
        private const val ARG_AVAILABLE = "available"
        private const val ARG_APPROVAL = "approval"
        private const val ARG_DAYS = "days"
        private const val ARG_START = "start"
        private const val ARG_END = "end"
        private const val ARG_DATE = "date"

        fun newInstance(room: BookingRoom, date: String) = BookingSlotsDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_ID, room.id)
                putString(ARG_NAME, room.name)
                putString(ARG_TYPE, room.type)
                putInt(ARG_CAPACITY, room.capacity)
                putString(ARG_LOCATION, room.location)
                putBoolean(ARG_AVAILABLE, room.available)
                putBoolean(ARG_APPROVAL, room.approvalRequired)
                putInt(ARG_DAYS, room.daysAhead)
                putString(ARG_START, room.bookStart)
                putString(ARG_END, room.bookEnd)
                putString(ARG_DATE, date)
            }
        }
    }
}
