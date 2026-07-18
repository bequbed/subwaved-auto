package com.powerpoppalace.subwaveauto.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the update-check version logic (v0.7 Tier-1 item 3):
 * [parseVersion] and [isNewerVersion] gate the "Update available" line, and
 * the failure direction matters — a malformed tag must read as NOT newer, so
 * the app never nags over junk it can't compare.
 */
class UpdateCheckLogicTest {

    // --- parseVersion ---

    @Test
    fun parseVersion_plainAndVPrefixed() {
        assertEquals(listOf(0, 7, 0), parseVersion("0.7.0"))
        assertEquals(listOf(0, 7, 0), parseVersion("v0.7.0"))
        assertEquals(listOf(1, 0), parseVersion("V1.0"))
        assertEquals(listOf(2), parseVersion("2"))
    }

    @Test
    fun parseVersion_trailingNonDigitsPerSegmentDropped() {
        assertEquals(listOf(1, 0), parseVersion("1.0-rc"))
        assertEquals(listOf(0, 7, 1), parseVersion("v0.7.1beta"))
    }

    @Test
    fun parseVersion_junkIsNull() {
        assertNull(parseVersion(""))
        assertNull(parseVersion("v"))
        assertNull(parseVersion("latest"))
        assertNull(parseVersion("0..1"))
        assertNull(parseVersion(".7"))
    }

    // --- isNewerVersion ---

    @Test
    fun newer_patchMinorMajor() {
        assertTrue(isNewerVersion("v0.7.1", "0.7.0"))
        assertTrue(isNewerVersion("v0.8.0", "0.7.9"))
        assertTrue(isNewerVersion("v1.0.0", "0.9.9"))
    }

    @Test
    fun equalOrOlder_notNewer() {
        assertFalse(isNewerVersion("v0.7.0", "0.7.0"))
        assertFalse(isNewerVersion("v0.6.1", "0.7.0"))
        // Missing segments compare as zero: "0.7" == "0.7.0".
        assertFalse(isNewerVersion("v0.7", "0.7.0"))
        assertFalse(isNewerVersion("v0.7.0", "0.7"))
    }

    @Test
    fun differentLengths_compareNumerically() {
        assertTrue(isNewerVersion("v0.7.0.1", "0.7.0"))
        assertFalse(isNewerVersion("v0.7", "0.7.0.1"))
    }

    @Test
    fun malformed_neverNewer() {
        assertFalse(isNewerVersion("latest", "0.7.0"))
        assertFalse(isNewerVersion("", "0.7.0"))
        assertFalse(isNewerVersion("v0.8.0", "not-a-version"))
    }

    @Test
    fun numericNotLexicographic() {
        // "0.10.0" > "0.9.0" numerically even though it sorts lower as a string.
        assertTrue(isNewerVersion("v0.10.0", "0.9.0"))
    }
}
