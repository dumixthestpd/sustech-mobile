package edu.sustech.mobile.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MemoryStoreTest {

    private fun bytes(size: Int): ByteArray = ByteArray(size) { 7 }

    @Before
    fun clean() = MemoryStore.clear()

    @Test
    fun `put then find returns the same file`() {
        val file = MemoryStore.put("k", "a.pdf", "application/pdf", bytes(10))
        assertNotNull(MemoryStore.find("k"))
        assertNotNull(MemoryStore.byToken(file.token))
        assertEquals(1, MemoryStore.size())
    }

    @Test
    fun `a pinned entry survives eviction`() {
        // Fill past the 48 MB budget: the first, pinned file must stay.
        val pinned = MemoryStore.put("keep", "keep.pdf", "application/pdf", bytes(24 * 1024 * 1024))
        MemoryStore.pin(pinned.token)
        MemoryStore.put("a", "a.pdf", "application/pdf", bytes(24 * 1024 * 1024))
        MemoryStore.put("b", "b.pdf", "application/pdf", bytes(24 * 1024 * 1024))
        assertNotNull(MemoryStore.find("keep"))
        assertTrue(MemoryStore.isPinned(pinned.token))
        assertNull(MemoryStore.find("a"))
    }

    @Test
    fun `unpinning lets the entry be evicted`() {
        val file = MemoryStore.put("keep", "keep.pdf", "application/pdf", bytes(24 * 1024 * 1024))
        MemoryStore.pin(file.token)
        MemoryStore.unpin(file.token)
        MemoryStore.put("a", "a.pdf", "application/pdf", bytes(24 * 1024 * 1024))
        MemoryStore.put("b", "b.pdf", "application/pdf", bytes(24 * 1024 * 1024))
        assertNull(MemoryStore.find("keep"))
        assertNull(MemoryStore.byToken(file.token))
    }

    @Test
    fun `clear empties both maps and the byte count`() {
        MemoryStore.put("k", "a.pdf", "application/pdf", bytes(10))
        MemoryStore.clear()
        assertEquals(0, MemoryStore.size())
        assertEquals(0L, MemoryStore.totalBytes())
    }
}
