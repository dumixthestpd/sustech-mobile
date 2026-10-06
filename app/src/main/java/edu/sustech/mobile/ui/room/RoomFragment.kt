package edu.sustech.mobile.ui.room

import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import edu.sustech.mobile.R
import edu.sustech.mobile.core.ApiException
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.library.room.RoomApi
import edu.sustech.mobile.library.room.RoomInfo
import edu.sustech.mobile.ui.ListFragment
import java.util.Date

/**
 * The IC library's discussion rooms: each room, the floor it is on, its capacity and
 * whether it is taken, with a search box and filters (free / taken / one chip per
 * floor) so 26 rooms are browsable rather than a wall.
 *
 * Tapping a room opens the booking sheet. Rooms whose minimum capacity is 3+ need the
 * booker plus two co-applicants, so the sheet asks for their student ids and this
 * screen resolves them before anything is sent.
 *
 * Needs the campus network: off campus the service refuses before any sign-in, and
 * that is reported as a location problem rather than a login one.
 */
class RoomFragment : ListFragment<RoomInfo>(R.layout.fragment_rooms), BookDialog.Listener {

    private var note: TextView? = null
    private var mine: TextView? = null
    private var search: EditText? = null
    private var filters: ChipGroup? = null

    /** Everything the service returned; filtering happens on top of this. */
    private var all: List<RoomInfo> = emptyList()

    /** Floors currently offered as chips, so the chip row is only rebuilt on change. */
    private var floors: List<String> = emptyList()

    private var selected = FILTER_ALL

    /** How many reservations this account is holding. */
    private var booked = 0

    override fun rowLayout() = R.layout.item_room

    override fun cachePrefix() = "rooms.all."

    override fun emptyText() = getString(R.string.rooms_no_match)

    override fun onReady(view: View) {
        note = view.findViewById(R.id.rooms_note)
        mine = view.findViewById(R.id.rooms_mine)
        search = view.findViewById(R.id.rooms_search)
        filters = view.findViewById(R.id.rooms_filters)
        note?.text = getString(R.string.rooms_loading)
        mine?.visibility = View.GONE
        mine?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.service_container, MyRoomsFragment())
                .addToBackStack("rooms_mine")
                .commit()
        }
        search?.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) = apply()
            },
        )
    }

    override suspend fun fetch(): List<RoomInfo> {
        val rooms = App.rooms.allRooms()
        // The count is a separate call; if it fails, the list is still worth showing.
        booked = runCatching { App.rooms.count() }.getOrDefault(0)
        return rooms
    }

    override fun onLoaded(rows: List<RoomInfo>) {
        all = rows
        buildFilters(rows)
        apply()
    }

    /** The search text and the chosen chip, applied to the loaded list. */
    private fun apply() {
        if (!isAdded) return
        val query = search?.text?.toString()?.trim().orEmpty()
        val shown = all.filter { room ->
            val matchesText = query.isEmpty() ||
                room.name.contains(query, ignoreCase = true) ||
                room.lab.contains(query, ignoreCase = true) ||
                room.people.contains(query, ignoreCase = true)
            val matchesFilter = when (selected) {
                FILTER_FREE -> room.free
                FILTER_TAKEN -> !room.free
                FILTER_ALL -> true
                else -> room.lab == selected
            }
            matchesText && matchesFilter
        }
        adapter.submit(shown)
        showEmpty(shown.isEmpty(), emptyText())

        val free = all.count { it.free }
        note?.text = getString(R.string.rooms_note, free, all.size, booked)
        mine?.visibility = View.VISIBLE
        mine?.text = getString(R.string.rooms_mine_link, booked)
    }

    /** Free/taken plus one chip per floor, rebuilt only when the floors change. */
    private fun buildFilters(rooms: List<RoomInfo>) {
        val group = filters ?: return
        val found = rooms.map { it.lab }.distinct()
        if (found == floors && group.childCount > 0) return
        floors = found
        group.removeAllViews()
        chip(group, getString(R.string.rooms_filter_all), FILTER_ALL)
        chip(group, getString(R.string.rooms_filter_free), FILTER_FREE)
        chip(group, getString(R.string.rooms_filter_taken), FILTER_TAKEN)
        found.forEach { chip(group, it, it) }
        group.check(group.getChildAt(0).id)
    }

    private fun chip(group: ChipGroup, label: String, value: String) {
        val chip = Chip(requireContext())
        chip.id = View.generateViewId()
        chip.text = label
        chip.isCheckable = true
        chip.tag = value
        chip.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                selected = value
                apply()
            }
        }
        group.addView(chip)
    }

    /** Called by the sheet once the slot is confirmed — this is the actual booking. */
    override fun onBook(
        room: RoomInfo,
        begin: Date,
        end: Date,
        title: String,
        studentIds: List<String>,
    ) {
        viewLifecycleOwner.runIo(
            block = {
                val context = App.context
                // Resolve every student id through the service's own lookup, and
                // refuse before sending if one cannot be placed.
                val accNos = studentIds.map { sid ->
                    val member = App.rooms.findMember(sid)
                    if (member == null || member.unavailable) {
                        throw ApiException(context.getString(R.string.rooms_member_unknown, sid))
                    }
                    member.accNo
                }
                if (room.needsMembers && accNos.size < RoomApi.MIN_CO_APPLICANTS) {
                    throw ApiException(
                        context.getString(
                            R.string.rooms_member_needed,
                            room.people,
                            RoomApi.MIN_CO_APPLICANTS,
                        ),
                    )
                }
                App.rooms.book(room.devId, begin, end, title, accNos)
            },
            onOk = {
                if (!isAdded) return@runIo
                android.widget.Toast.makeText(
                    requireContext(),
                    R.string.rooms_booked,
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                // The room's state just changed; reload rather than patching the row.
                load(force = true)
            },
            onErr = { error ->
                if (!isAdded) return@runIo
                showError(error)
            },
        )
    }

    override fun bindRow(view: View, item: RoomInfo, position: Int) {
        view.findViewById<TextView>(R.id.room_name).text = item.name
        view.findViewById<TextView>(R.id.room_where).text = listOf(
            item.lab,
            if (item.needsMembers) {
                getString(R.string.rooms_needs_members, RoomApi.MIN_CO_APPLICANTS)
            } else {
                ""
            },
        ).filter { it.isNotBlank() }.joinToString(" · ")
        view.findViewById<TextView>(R.id.room_status).text = getString(
            if (item.free) R.string.rooms_free_now else R.string.rooms_taken_now,
        )
        view.setOnClickListener {
            // childFragmentManager, so the sheet's parentFragment is this screen —
            // that is how the confirmed slot gets back here to be sent. On the
            // parent manager the dialog's parentFragment is null and the confirm
            // silently does nothing.
            BookDialog.newInstance(item).show(childFragmentManager, "book")
        }
    }

    private companion object {
        const val FILTER_ALL = "*all"
        const val FILTER_FREE = "*free"
        const val FILTER_TAKEN = "*taken"
    }
}
