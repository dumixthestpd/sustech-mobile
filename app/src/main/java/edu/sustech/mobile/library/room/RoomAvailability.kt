package edu.sustech.mobile.library.room

enum class RoomAvailability { AVAILABLE, OCCUPIED, CLOSED, UNKNOWN }

/** Whole-interval coverage; a missing calendar is never a free room. */
object RoomInterval {
    fun state(slots: List<RoomSlot>?, begin: Int, end: Int): RoomAvailability {
        require(begin in 0..1439 && end in 1..1440 && begin < end)
        if (slots == null) return RoomAvailability.UNKNOWN
        fun minute(clock: String): Int? = runCatching {
            val parts = clock.split(':')
            require(parts.size == 2)
            val hour = parts[0].toInt(); val min = parts[1].toInt()
            require(hour in 0..23 && min in 0..59)
            hour * 60 + min
        }.getOrNull()
        val ranges = slots.mapNotNull { slot ->
            val start = minute(slot.start) ?: return RoomAvailability.UNKNOWN
            val finish = minute(slot.end)?.let { if (it == 0 && start > 0) 1440 else it } ?: return RoomAvailability.UNKNOWN
            if (finish <= start) return RoomAvailability.UNKNOWN
            if (start >= end || finish <= begin) null else Triple(start, finish, slot.state)
        }.sortedBy { it.first }
        if (ranges.any { it.third == RoomSlotState.RESERVED || it.third == RoomSlotState.IN_USE }) return RoomAvailability.OCCUPIED
        if (ranges.any { it.third == RoomSlotState.CLOSED }) return RoomAvailability.CLOSED
        var cursor = begin
        for ((start, finish) in ranges) {
            if (start > cursor) return RoomAvailability.UNKNOWN
            cursor = maxOf(cursor, finish)
        }
        return if (cursor >= end) RoomAvailability.AVAILABLE else RoomAvailability.UNKNOWN
    }
}
