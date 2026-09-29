package com.venturilogic.grip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForceSessionTest {
    @Test
    fun unitConversionRoundTrips() {
        assertEquals(1.0, ForceUnit.LBS.convert(ForceUnit.KG.convert(1.0, ForceUnit.LBS), ForceUnit.KG), 1e-12)
        assertEquals(0.45359237, ForceUnit.LBS.convert(1.0, ForceUnit.KG), 1e-8)
        assertEquals(9.80665, ForceUnit.KG.convert(1.0, ForceUnit.N), 1e-12)
    }

    @Test
    fun statsInDisplayUnit() {
        val s = ForceSession(ForceUnit.LBS, displayUnit = ForceUnit.LBS)
        s.ingest(10.0, 0)
        s.ingest(30.0, 10)
        val m = s.ingest(20.0, 20)
        assertEquals(20.0, m.current)
        assertEquals(30.0, m.peak)
        assertEquals(10.0, m.min)
        assertEquals(20.0, m.mean, 1e-12)
        assertEquals(3, m.sampleIndex)

        s.displayUnit = ForceUnit.KG
        assertEquals(30.0 * 0.45359237, s.ingest(0.0, 30).peak, 1e-9)
    }

    @Test
    fun softwareTareAveragesWindowThenSubtracts() {
        val s = ForceSession(ForceUnit.KG)
        assertTrue(s.tare(nowMs = 0, durationMs = 100))
        assertFalse(s.tare(nowMs = 1)) // already running
        s.ingest(2.0, 0)
        assertTrue(s.ingest(4.0, 50).isTaring)
        val done = s.ingest(6.0, 100) // window closes, offset = avg(2,4,6) = 4
        assertFalse(done.isTaring)
        assertEquals(4.0, s.tareOffsetNative)
        assertEquals(6.0, s.ingest(10.0, 110).current)
        s.clearTare()
        assertEquals(10.0, s.ingest(10.0, 120).current)
    }

    @Test
    fun activityIsDebounced() {
        val s = ForceSession(ForceUnit.KG, activity = ActivityConfig(threshold = 2.5, durationMs = 1_000))
        assertFalse(s.ingest(5.0, 0).isActive)
        assertFalse(s.ingest(5.0, 999).isActive)
        assertTrue(s.ingest(5.0, 1_000).isActive)
        // A brief dip does not flip it back.
        assertTrue(s.ingest(0.0, 1_100).isActive)
        assertTrue(s.ingest(5.0, 1_200).isActive)
        assertTrue(s.ingest(0.0, 1_300).isActive)
        assertFalse(s.ingest(0.0, 2_300).isActive)
    }

    @Test
    fun samplingRateAfterOneSecond() {
        val s = ForceSession(ForceUnit.LBS)
        var last: ForceMeasurement? = null
        for (t in 0..1_000 step 25) last = s.ingest(1.0, t.toLong()) // 40 Hz
        assertEquals(41, last!!.samplingRateHz) // inclusive window [0, 1000]
        assertNull(ForceSession(ForceUnit.LBS).ingest(1.0, 0).samplingRateHz)
    }

    @Test
    fun resetStatsKeepsTare() {
        val s = ForceSession(ForceUnit.KG)
        s.tare(0, 0)
        s.ingest(3.0, 0)
        s.ingest(50.0, 10)
        s.resetStats()
        val m = s.ingest(4.0, 20)
        assertEquals(1.0, m.peak)
        assertEquals(1, m.sampleIndex)
    }
}
