package edu.sustech.mobile.ui.room

import android.view.View
import android.widget.TextView
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.core.runIo
import edu.sustech.mobile.library.room.RoomInfo
import edu.sustech.mobile.ui.ListFragment

/**
 * The IC library's discussion rooms, right now: each room, the floor it is on, and
 * whether it is taken. Tapping one opens the booking sheet — a room that is busy now
 * can still be free later, which is what the sheet is for.
 *
 * Two questions, one screen: what is free, and what this account has booked (the
 * note's second line).
 *
 * Needs the campus network: off campus the service refuses before any sign-in, and
 * that is reported as a location problem rather than a login one.
 */
class RoomFragment : ListFragment<RoomInfo>(R.layout.fragment_rooms), BookDialog.Listener {

    private var note: TextView? = null
    private var mine: TextView? = null

    /** How many reservations this account is holding. */
    private var booked = 0

    override fun rowLayout() = R.layout.item_room

    override fun cachePrefix() = "rooms.all."

    override fun emptyText() = getString(R.string.rooms_empty)

    override fun onReady(view: View) {
        note = view.findViewById(R.id.rooms_note)
        mine = view.findViewById(R.id.rooms_mine)
        note?.text = getString(R.string.rooms_loading)
        mine?.visibility = View.GONE
        mine?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.service_container, MyRoomsFragment())
                .addToBackStack("rooms_mine")
                .commit()
        }
    }

    override suspend fun fetch(): List<RoomInfo> {
        val rooms = App.rooms.allRooms()
        // The count is a separate call; if it fails, the list is still worth showing.
        booked = runCatching { App.rooms.count() }.getOrDefault(0)
        return rooms
    }

    override fun onLoaded(rows: List<RoomInfo>) {
        val free = rows.count { it.free }
        note?.text = getString(R.string.rooms_note, free, rows.size, booked)
        mine?.visibility = View.VISIBLE
        mine?.text = getString(R.string.rooms_mine_link, booked)
    }

    /** Called by the sheet once the slot is confirmed — this is the actual booking. */
    override fun onBook(room: RoomInfo, begin: java.util.Date, end: java.util.Date, title: String) {
        viewLifecycleOwner.runIo(
            block = { App.rooms.book(room.devId, begin, end, title) },
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
        view.findViewById<TextView>(R.id.room_where).text = item.lab
        view.findViewById<TextView>(R.id.room_status).text = getString(
            if (item.free) R.string.rooms_free_now else R.string.rooms_taken_now,
        )
        view.setOnClickListener {
            BookDialog.newInstance(item).show(parentFragmentManager, "book")
        }
    }
}
