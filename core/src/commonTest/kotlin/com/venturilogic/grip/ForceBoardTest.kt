package com.venturilogic.grip

import com.venturilogic.grip.devices.ForceBoard
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ForceBoardTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesSingleSample() {
        // 1 sample, 0x00 0x00 0x19 = 25 lbs
        assertContentEquals(intArrayOf(25), ForceBoard.parseForceData(bytes(0x00, 0x01, 0x00, 0x00, 0x19)))
    }

    @Test
    fun parsesMaxPacketOfSixSamples() {
        val packet = bytes(
            0x00, 0x06,
            0x00, 0x00, 0x01,
            0x00, 0x00, 0x02,
            0x00, 0x01, 0x00, // 256
            0x00, 0x01, 0x2C, // 300
            0x00, 0xFF, 0xFF, // 65535
            0x01, 0x00, 0x00, // 32768 (PitchSix multiplier on byte 0)
        )
        assertEquals(20, packet.size)
        assertContentEquals(intArrayOf(1, 2, 256, 300, 65535, 32768), ForceBoard.parseForceData(packet))
    }

    @Test
    fun dropsTruncatedTrailingSample() {
        // Declares 2 samples but only carries 1 full one.
        assertContentEquals(intArrayOf(7), ForceBoard.parseForceData(bytes(0x00, 0x02, 0x00, 0x00, 0x07, 0x00, 0x00)))
    }

    @Test
    fun emptyOrShortPacketsYieldNothing() {
        assertEquals(0, ForceBoard.parseForceData(bytes()).size)
        assertEquals(0, ForceBoard.parseForceData(bytes(0x00)).size)
        assertEquals(0, ForceBoard.parseForceData(bytes(0x00, 0x00)).size)
    }

    @Test
    fun thresholdMatchesPublicApiExample() {
        // API doc: 0x000019 = 25 lbs
        assertContentEquals(bytes(0x00, 0x00, 0x19), ForceBoard.thresholdPayload(25))
        assertContentEquals(bytes(0x01, 0x02, 0x03), ForceBoard.thresholdPayload(0x010203))
    }

    @Test
    fun modeBytes() {
        assertContentEquals(bytes(0x04), ForceBoard.Mode.STREAM.payload())
        assertContentEquals(bytes(0x07), ForceBoard.Mode.IDLE.payload())
    }

    @Test
    fun battery() {
        assertEquals(87, ForceBoard.parseBatteryPercent(bytes(87)))
        assertEquals(null, ForceBoard.parseBatteryPercent(bytes()))
    }
}
