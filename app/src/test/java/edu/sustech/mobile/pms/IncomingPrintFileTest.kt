package edu.sustech.mobile.pms

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IncomingPrintFileTest {
    @get:Rule val cache = TemporaryFolder()

    @Test fun `shared filename and original bytes survive the handoff`() {
        val bytes = "%PDF-1.4 teacher handout".toByteArray()
        val file = IncomingPrintFile.copy(cache.root, "课程材料.pdf") { ByteArrayInputStream(bytes) }
        assertEquals("课程材料.pdf", file.name)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun `foreign paths cannot escape the private directory`() {
        for (name in listOf("../../outside.pdf", "C:\\folder\\outside.pdf")) {
            val file = IncomingPrintFile.copy(cache.root, name) { ByteArrayInputStream(byteArrayOf(1)) }
            assertEquals("outside.pdf", file.name)
            assertEquals(cache.root.canonicalFile, requireNotNull(requireNotNull(file.parentFile).parentFile).canonicalFile)
        }
    }

    @Test fun `same named files never overwrite a previous selection`() {
        val first = IncomingPrintFile.copy(cache.root, "handout.pdf") { ByteArrayInputStream(byteArrayOf(1)) }
        val second = IncomingPrintFile.copy(cache.root, "handout.pdf") { ByteArrayInputStream(byteArrayOf(2)) }
        assertNotEquals(first, second)
        assertArrayEquals(byteArrayOf(1), first.readBytes())
        assertArrayEquals(byteArrayOf(2), second.readBytes())
    }

    @Test fun `oversized input is rejected and its partial copy removed`() {
        assertThrows(IncomingPrintFile.TooLarge::class.java) {
            IncomingPrintFile.copy(cache.root, "large.pdf", maxBytes = 4) { ByteArrayInputStream(ByteArray(5)) }
        }
        assertEquals(0, cache.root.listFiles()!!.size)
    }

    @Test fun `missing stream is a read error and leaves no copy`() {
        assertThrows(IOException::class.java) {
            IncomingPrintFile.copy(cache.root, "gone.pdf") { null }
        }
        assertEquals(0, cache.root.listFiles()!!.size)
    }

    @Test fun `provider failure after some bytes removes the partial file`() {
        assertThrows(IOException::class.java) {
            IncomingPrintFile.copy(cache.root, "broken.pdf") {
                object : InputStream() {
                    var reads = 0
                    override fun read(): Int {
                        if (reads++ < 3) return 1
                        throw IOException("Sender disappeared")
                    }
                }
            }
        }
        assertEquals(0, cache.root.listFiles()!!.size)
    }
}
