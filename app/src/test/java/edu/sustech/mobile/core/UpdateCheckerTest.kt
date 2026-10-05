package edu.sustech.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests for the version comparison that gates update detection.
 * Wire shapes come from the GitHub Releases API of this repository
 * (latest release asset: `sustech-mobile-v<version>-debug.apk`).
 */
class UpdateCheckerTest {

    @Test
    fun `running older than latest is detected`() {
        assertTrue(compareVersionsInternal("0.3.20-shortcuts", "0.3.21") < 0)
    }

    @Test
    fun `running equal to latest is not an update`() {
        assertEquals(0, compareVersionsInternal("0.3.11", "0.3.11"))
    }

    @Test
    fun `running newer than latest is not an update`() {
        assertTrue(compareVersionsInternal("0.3.20-shortcuts", "0.3.11") > 0)
    }

    @Test
    fun `suffix is ignored on both sides`() {
        assertEquals(0, compareVersionsInternal("0.3.20-shortcuts", "0.3.20"))
    }

    @Test
    fun `different segment counts compare numerically`() {
        assertTrue(compareVersionsInternal("0.3", "0.3.1") < 0)
        assertTrue(compareVersionsInternal("0.3.1.2", "0.3.1") > 0)
    }

    @Test
    fun `non numeric segments count as zero`() {
        assertEquals(0, compareVersionsInternal("0.3.beta", "0.3.0"))
    }

    @Test
    fun `leading v on the running name does not break the parse`() {
        // VERSION_NAME never carries the leading v, but the tag does — the
        // checker strips it before comparing; this pins that behaviour.
        assertTrue(compareVersionsInternal("v0.3.9", "0.3.10") < 0)
    }
}

/** Test bridge: compareVersions is internal; expose it to this test tree. */
fun compareVersionsInternal(running: String, latest: String): Int =
    UpdateChecker.compareVersions(running, latest)
