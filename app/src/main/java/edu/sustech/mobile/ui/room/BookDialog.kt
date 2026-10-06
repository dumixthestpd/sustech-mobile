package edu.sustech.mobile.ui.room

import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import edu.sustech.mobile.R
import edu.sustech.mobile.core.App
import edu.sustech.mobile.library.room.RoomApi
import edu.sustech.mobile.library.room.RoomInfo
import edu.sustech.mobile.library.room.RoomMember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Books one room: pick the day, the start time and the length, name the co-applicants
 * if the room needs them, then confirm the exact slot. The confirmation is not a
 * formality — the room is taken the moment this is sent, and the library's rules (two
 * days ahead, two hours a session, a 3+ person room needs the booker plus two
 * co-applicants, cancel at least ten minutes before the start) are the service's, not
 * this screen's.
 *
 * 🔴 A room whose *minimum* capacity is 3+ is refused by the service unless the booking
 * carries co-applicants, so this offers one student-id box per co-applicant and asks
 * the service who each one is. **The name appears under the box as you type** — that is
 * the check: you see it resolved to a person, not merely spelled right. Nothing is sent
 * while a box is unresolved or while fewer than the required number are named, and
 * ids are never split on punctuation (people type 、 and ， and trailing spaces).
 *
 * 🔴 The start is a plain time picker, not a list built from the room's `openTimes`:
 * the service returns `07:00–07:00` with `openLimit: 0` for every room measured, so
 * those values carry no window and a list built from them would be an invention. The
 * service is the authority on what is bookable and its refusal is shown as-is.
 */
class BookDialog : DialogFragment() {

    /** Whoever opened the sheet does the sending, so it runs on a live lifecycle. */
    interface Listener {
        fun onBook(
            room: RoomInfo,
            begin: Date,
            end: Date,
            title: String,
            coApplicants: List<RoomMember>,
        )
    }

    /** One co-applicant box, what the service said about it, and the pending lookup. */
    private class MemberRow(val input: EditText, val status: TextView) {
        var job: Job? = null
        var member: RoomMember? = null
    }

    private lateinit var startButton: TextView
    private var startHour = 0
    private var startMinute = 0
    private var needsMembers = false
    private var memberLabel: TextView? = null
    private val memberRows = mutableListOf<MemberRow>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val room = RoomInfo(
            devId = requireArguments().getInt(ARG_DEV),
            name = requireArguments().getString(ARG_NAME).orEmpty(),
            lab = requireArguments().getString(ARG_LAB).orEmpty(),
            people = requireArguments().getString(ARG_PEOPLE).orEmpty(),
            needsMembers = requireArguments().getBoolean(ARG_NEEDS),
            minMinutes = requireArguments().getInt(ARG_MIN),
            free = true,
        )
        needsMembers = room.needsMembers
        val view = layoutInflater.inflate(R.layout.dialog_book, null)
        view.findViewById<TextView>(R.id.book_room).text = listOf(
            room.name,
            room.lab,
            room.people,
        ).filter { it.isNotBlank() }.joinToString(" · ")
        view.findViewById<TextView>(R.id.book_policy).text = getString(R.string.rooms_policy)

        val days = RoomApi.bookableDays()
        val daySpinner = view.findViewById<Spinner>(R.id.book_day)
        daySpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            days.map { DAY.format(it) },
        )

        // Default to the next half hour from now.
        val now = Calendar.getInstance()
        startHour = now.get(Calendar.HOUR_OF_DAY)
        startMinute = if (now.get(Calendar.MINUTE) < 30) 30 else 0
        if (startMinute == 0) startHour = (startHour + 1) % 24
        startButton = view.findViewById(R.id.book_start)
        showStart()
        startButton.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hour, minute -> startHour = hour; startMinute = minute; showStart() },
                startHour,
                startMinute,
                true,
            ).show()
        }

        val lengths = LENGTHS.filter { it <= RoomApi.MAX_MINUTES }
        val lengthSpinner = view.findViewById<Spinner>(R.id.book_length)
        lengthSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            lengths.map { getString(R.string.rooms_minutes, it) },
        )

        val topic = view.findViewById<EditText>(R.id.book_topic)
        topic.setText(RoomApi.DEFAULT_TITLE)

        // A 3+ person room will be refused without co-applicants, so ask for them here.
        val memberLabel = view.findViewById<TextView>(R.id.book_members_label)
        val memberBox = view.findViewById<LinearLayout>(R.id.book_members_rows)
        val memberAdd = view.findViewById<TextView>(R.id.book_members_add)
        if (room.needsMembers) {
            memberLabel.visibility = View.VISIBLE
            memberBox.visibility = View.VISIBLE
            memberAdd.visibility = View.VISIBLE
            this.memberLabel = memberLabel
            memberLabel.text = getString(R.string.rooms_members_label, RoomApi.MIN_CO_APPLICANTS)
            memberAdd.text = getString(R.string.rooms_members_add)
            repeat(RoomApi.MIN_CO_APPLICANTS) { addRow(memberBox) }
            memberAdd.setOnClickListener {
                if (memberRows.size >= MAX_MEMBERS) {
                    toast(getString(R.string.rooms_members_max, MAX_MEMBERS))
                } else {
                    addRow(memberBox)
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.rooms_book_title)
            .setView(view)
            .setPositiveButton(R.string.rooms_book, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val day = days[daySpinner.selectedItemPosition]
                        val begin = at(day, startHour, startMinute)
                        val end = Date(
                            begin.time + lengths[lengthSpinner.selectedItemPosition] * 60_000L,
                        )
                        val members = readyMembers() ?: return@setOnClickListener
                        confirm(
                            room = room,
                            begin = begin,
                            end = end,
                            title = topic.text.toString().trim(),
                            members = members,
                        )
                    }
                    // Nothing can be sent until the service has named the required
                    // number of co-applicants — the button stays shut rather than
                    // refusing after the press.
                    refreshReady()
                }
            }
    }

    // -- Co-applicants --------------------------------------------------------

    private fun addRow(box: LinearLayout) {
        val line = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.rooms_members_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = TextView(requireContext()).apply {
            textSize = 12f
            setTextColor(secondaryColor())
            setPadding(0, 0, 0, PAD)
        }
        val entry = MemberRow(input, status)
        val drop = TextView(requireContext()).apply {
            text = "✕"
            textSize = 15f
            setPadding(PAD, PAD, PAD, PAD)
            setOnClickListener {
                // Drop the box *and* its lookup from the tally, or a deleted box would
                // keep the booking button shut.
                box.removeView(line.parent as View)
                entry.job?.cancel()
                memberRows.remove(entry)
                refreshReady()
            }
        }
        line.addView(input)
        line.addView(drop)

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(line)
            addView(status)
        }
        box.addView(row)

        memberRows += entry
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    resolve(entry, s?.toString()?.trim().orEmpty())
                }
            },
        )
    }

    /**
     * Asks the service who this id is and shows the answer under the box. The lookup is
     * delayed while typing and superseded by the next keystroke, so the name shown
     * always belongs to what is in the box.
     */
    private fun resolve(entry: MemberRow, sid: String) {
        entry.job?.cancel()
        entry.member = null
        entry.status.text = ""
        if (sid.isEmpty()) return
        entry.status.text = getString(R.string.rooms_member_checking)
        entry.job = lifecycleScope.launch {
            delay(LOOKUP_SETTLE_MS)
            val found = try {
                withContext(Dispatchers.IO) { App.rooms.findMember(sid) }
            } catch (t: Throwable) {
                null
            }
            if (!isAdded || entry.input.text.toString().trim() != sid) return@launch
            entry.member = found
            entry.status.setTextColor(secondaryColor())
            entry.status.text = when {
                found == null -> getString(R.string.rooms_member_missing)
                found.unavailable -> getString(R.string.rooms_member_unavailable)
                found.name.isBlank() -> getString(R.string.rooms_member_found, sid, found.sid)
                else -> getString(R.string.rooms_member_found, found.name, found.sid)
            }
            refreshReady()
        }
    }

    /**
     * Keeps the booking button shut until the service has named at least the required
     * number of co-applicants, and says how far along that is. A 1-3 person room has
     * nothing to wait for and the button is open from the start.
     */
    private fun refreshReady() {
        val button = (dialog as? AlertDialog)?.getButton(AlertDialog.BUTTON_POSITIVE) ?: return
        val named = memberRows.mapNotNull { it.member }.filter { !it.unavailable }
        val pending = memberRows.any { it.input.text.isNotBlank() && it.member == null }
        val enough = named.size >= RoomApi.MIN_CO_APPLICANTS
        button.isEnabled = !needsMembers || (enough && !pending)
        if (!needsMembers) return
        memberLabel?.text = buildString {
            append(getString(R.string.rooms_members_label, RoomApi.MIN_CO_APPLICANTS))
            if (named.isNotEmpty() || memberRows.any { it.input.text.isNotBlank() }) {
                append(" · ")
                append(
                    getString(
                        R.string.rooms_members_progress,
                        named.size,
                        RoomApi.MIN_CO_APPLICANTS,
                    ),
                )
            }
        }
    }

    /**
     * The co-applicants, or null after saying why not. Ids are taken exactly as typed —
     * one box, one id — so a Chinese comma or a stray space can never split one into two.
     */
    private fun readyMembers(): List<RoomMember>? {
        if (!needsMembers) return emptyList()
        val typed = memberRows.filter { it.input.text.isNotBlank() }
        if (typed.any { it.member == null }) {
            toast(getString(R.string.rooms_members_pending))
            return null
        }
        val members = typed.mapNotNull { it.member }
        if (members.size < RoomApi.MIN_CO_APPLICANTS) {
            toast(
                getString(
                    R.string.rooms_members_required,
                    RoomApi.MIN_CO_APPLICANTS,
                ),
            )
            return null
        }
        if (members.any { it.unavailable }) {
            toast(getString(R.string.rooms_member_unavailable))
            return null
        }
        return members
    }

    // -- The rest -------------------------------------------------------------

    private fun showStart() {
        startButton.text = String.format(Locale.US, "%02d:%02d", startHour, startMinute)
    }

    /** States exactly what will be sent before it is sent. */
    private fun confirm(
        room: RoomInfo,
        begin: Date,
        end: Date,
        title: String,
        members: List<RoomMember>,
    ) {
        val lines = mutableListOf(
            room.name,
            "${DAY.format(begin)} ${SLOT.format(begin)}–${SLOT.format(end)}",
        )
        if (members.isNotEmpty()) {
            lines += getString(
                R.string.rooms_members_confirm,
                members.joinToString("、") { it.name.ifBlank { it.sid } },
            )
        }
        lines += getString(R.string.rooms_book_confirm_warning)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.rooms_book_confirm_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.rooms_book) { _, _ ->
                (parentFragment as? Listener)?.onBook(room, begin, end, title, members)
                dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** [day] with the chosen hour and minute. */
    private fun at(day: Date, hour: Int, minute: Int): Date {
        val calendar = Calendar.getInstance()
        calendar.time = day
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.time
    }

    private fun toast(message: String) {
        if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    /** The app's secondary text colour, theme-dependent. */
    private fun secondaryColor(): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
        return value.data
    }

    companion object {
        private const val ARG_DEV = "devId"
        private const val ARG_NAME = "name"
        private const val ARG_LAB = "lab"
        private const val ARG_PEOPLE = "people"
        private const val ARG_NEEDS = "needsMembers"
        private const val ARG_MIN = "minMinutes"

        private val LENGTHS = listOf(30, 60, 90, 120)

        /** A 3-10 person room plus its booker: nine co-applicants is more than enough. */
        private const val MAX_MEMBERS = 9

        /** Let a student id finish being typed before asking who it is. */
        private const val LOOKUP_SETTLE_MS = 550L

        private const val PAD = 8

        private val DAY = SimpleDateFormat("yyyy-MM-dd EEE", Locale.US)
        private val SLOT = SimpleDateFormat("HH:mm", Locale.US)

        fun newInstance(room: RoomInfo): BookDialog = BookDialog().apply {
            arguments = Bundle().apply {
                putInt(ARG_DEV, room.devId)
                putString(ARG_NAME, room.name)
                putString(ARG_LAB, room.lab)
                putString(ARG_PEOPLE, room.people)
                putBoolean(ARG_NEEDS, room.needsMembers)
                putInt(ARG_MIN, room.minMinutes)
            }
        }
    }
}
