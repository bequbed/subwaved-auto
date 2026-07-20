package com.powerpoppalace.subwaveauto.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the v0.12 station-preset (de)serialization + upsert
 * helpers. org.json is a JVM test dependency, so these run without Android.
 */
class StationPresetTest {

    @Test
    fun roundTrip_preservesOrderAndFields() {
        val presets = listOf(
            StationPreset("Basement Transmission", "https://radio.plexservernz.org"),
            StationPreset("Power Pop Palace", "https://radio.powerpoppalace.com"),
        )
        val restored = presetsFromJson(presetsToJson(presets))
        assertEquals(presets, restored)
    }

    @Test
    fun fromJson_nullOrBlank_isEmpty() {
        assertEquals(emptyList<StationPreset>(), presetsFromJson(null))
        assertEquals(emptyList<StationPreset>(), presetsFromJson(""))
        assertEquals(emptyList<StationPreset>(), presetsFromJson("   "))
    }

    @Test
    fun fromJson_garbage_isEmpty() {
        assertEquals(emptyList<StationPreset>(), presetsFromJson("not json"))
        assertEquals(emptyList<StationPreset>(), presetsFromJson("{\"not\":\"an array\"}"))
    }

    @Test
    fun fromJson_skipsEntriesMissingUrl_andDefaultsBlankName() {
        val json = """[{"name":"A"},{"url":"https://b.example.com"},{"name":"C","url":"https://c.example.com"}]"""
        val parsed = presetsFromJson(json)
        assertEquals(2, parsed.size)
        // Entry with url but no name falls back to the url as its label.
        assertEquals(StationPreset("https://b.example.com", "https://b.example.com"), parsed[0])
        assertEquals(StationPreset("C", "https://c.example.com"), parsed[1])
    }

    @Test
    fun upsert_addsNew_last() {
        val start = listOf(StationPreset("A", "https://a.example.com"))
        val next = upsertPreset(start, StationPreset("B", "https://b.example.com"))
        assertEquals(listOf("A", "B"), next.map { it.name })
    }

    @Test
    fun upsert_sameUrl_updatesNameNoDuplicate() {
        val start = listOf(
            StationPreset("Old name", "https://a.example.com"),
            StationPreset("B", "https://b.example.com"),
        )
        val next = upsertPreset(start, StationPreset("New name", "https://a.example.com"))
        assertEquals(2, next.size)
        // The re-added URL moves to the end with its updated name.
        assertEquals(StationPreset("New name", "https://a.example.com"), next.last())
        assertEquals(1, next.count { it.url == "https://a.example.com" })
    }
}
