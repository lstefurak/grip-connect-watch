package com.venturilogic.grip

import com.venturilogic.grip.devices.Progressor
import com.venturilogic.grip.devices.Progressor.Command
import com.venturilogic.grip.devices.Progressor.Response
import com.venturilogic.grip.devices.Progressor.Sample
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressorTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Weight frame from (kg, µs) records, for frames too long to spell out. */
    private fun weightFrame(records: List<Pair<Float, Long>>): ByteArray {
        val frame = ByteArray(2 + records.size * 8)
        frame[0] = 0x01
        frame[1] = (records.size * 8).toByte()
        records.forEachIndexed { i, (kg, us) ->
            val bits = kg.toRawBits()
            for (b in 0..3) {
                frame[2 + i * 8 + b] = (bits shr (8 * b)).toByte()
                frame[6 + i * 8 + b] = (us shr (8 * b)).toByte()
            }
        }
        return frame
    }

    /** Checks parseWeightSamples against (kg, µs) pairs, and parseWeights against it. */
    private fun assertDecodes(expected: List<Pair<Double, Long>>, packet: ByteArray) {
        val samples = Progressor.parseWeightSamples(packet)
        assertEquals(expected.size, samples.size)
        expected.zip(samples).forEach { (e, s) ->
            assertEquals(e.first, s.weight, 1e-6)
            assertEquals(e.second, s.deviceTimeUs)
        }
        assertContentEquals(samples.map { it.weight }.toDoubleArray(), Progressor.parseWeights(packet))
    }

    private fun assertNoWeights(packet: ByteArray) {
        assertEquals(emptyList(), Progressor.parseWeightSamples(packet))
        assertEquals(0, Progressor.parseWeights(packet).size)
    }

    // Test vectors P1-P16 from the Progressor spec. Floats are little-endian IEEE-754,
    // e.g. 12.5 = 0x41480000 -> 00 00 48 41.

    // P1, upstream test: 12.5 kg at 1000 µs.
    private val p1 = bytes(0x01, 0x08, 0x00, 0x00, 0x48, 0x41, 0xE8, 0x03, 0x00, 0x00)

    // P2, upstream test: two records in one notification.
    private val p2 = bytes(
        0x01, 0x10,
        0x00, 0x00, 0x20, 0x41, 0xE8, 0x03, 0x00, 0x00, // 10.0 kg, 1000 µs
        0x00, 0x00, 0xA0, 0x41, 0xD0, 0x07, 0x00, 0x00, // 20.0 kg, 2000 µs
    )

    // P3: records 0-2 of a real unit capture (rickardenglund/tindeq-cli), re-framed with N = 0x18.
    private val p3 = bytes(
        0x01, 0x18,
        0x50, 0x98, 0xF0, 0xBD, 0xCC, 0x2B, 0x0F, 0x00,
        0x20, 0x7A, 0xF1, 0xBD, 0x13, 0x59, 0x0F, 0x00,
        0xE0, 0x2E, 0xF1, 0xBD, 0x5B, 0x86, 0x0F, 0x00,
    )

    // P4: records 13-14 of the same capture.
    private val p4 = bytes(
        0x01, 0x10,
        0x80, 0x7A, 0x02, 0xBE, 0x7A, 0x78, 0x11, 0x00,
        0xC8, 0x63, 0x04, 0xBE, 0xC2, 0xA5, 0x11, 0x00,
    )

    // P5, upstream test: +Inf, -Inf and NaN between two finite records.
    private val p5 = bytes(
        0x01, 0x28,
        0x00, 0x00, 0x80, 0x7F, 0xE8, 0x03, 0x00, 0x00, // +Inf, 1000 µs
        0x00, 0x00, 0x80, 0xFF, 0xD0, 0x07, 0x00, 0x00, // -Inf, 2000 µs
        0x00, 0x00, 0xC0, 0x7F, 0xB8, 0x0B, 0x00, 0x00, // NaN, 3000 µs
        0x00, 0x00, 0x20, 0x41, 0xA0, 0x0F, 0x00, 0x00, // 10.0 kg, 4000 µs
        0x00, 0x00, 0x70, 0x41, 0x88, 0x13, 0x00, 0x00, // 15.0 kg, 5000 µs
    )

    // P6: device times at 0, 2^32 - 1 and 2^31.
    private val p6 = bytes(
        0x01, 0x18,
        0x00, 0x00, 0x00, 0xC0, 0x00, 0x00, 0x00, 0x00, // -2.0 kg, 0 µs
        0x00, 0x00, 0x16, 0x43, 0xFF, 0xFF, 0xFF, 0xFF, // 150.0 kg, 4294967295 µs
        0x00, 0x00, 0xC0, 0xBF, 0x00, 0x00, 0x00, 0x80, // -1.5 kg, 2147483648 µs
    )

    // P8: N = 12, not a whole number of 8-byte records.
    private val p8 = bytes(0x01, 0x0C, 0x00, 0x00, 0x80, 0x3F, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)

    // P9: 1.0 kg at 1 µs, then 2 bytes past the declared payload.
    private val p9 = bytes(0x01, 0x08, 0x00, 0x00, 0x80, 0x3F, 0x01, 0x00, 0x00, 0x00, 0xAA, 0xBB)

    // P11: one NaN record.
    private val p11 = bytes(0x01, 0x08, 0x00, 0x00, 0xC0, 0x7F, 0xE8, 0x03, 0x00, 0x00)

    // P14: battery reply, u32 3700 mV.
    private val p14 = bytes(0x00, 0x04, 0x74, 0x0E, 0x00, 0x00)

    @Test
    fun parsesSingleSample() {
        assertEquals(listOf(Sample(12.5, 1000)), Progressor.parseWeightSamples(p1))
        assertContentEquals(doubleArrayOf(12.5), Progressor.parseWeights(p1))
    }

    @Test
    fun parsesMultipleSamplesInRecordOrder() {
        assertEquals(listOf(Sample(10.0, 1000), Sample(20.0, 2000)), Progressor.parseWeightSamples(p2))
        assertContentEquals(doubleArrayOf(10.0, 20.0), Progressor.parseWeights(p2))
    }

    @Test
    fun decodesRealUnitCapture() {
        assertDecodes(listOf(-0.117478013 to 994252L, -0.117908716 to 1005843L, -0.117765188 to 1017435L), p3)
        assertDecodes(listOf(-0.127420425 to 1144954L, -0.129286885 to 1156546L), p4)
    }

    @Test
    fun parsesMaxPacketOf31Samples() {
        assertContentEquals(p1, weightFrame(listOf(12.5f to 1000L))) // helper matches the spec layout
        // N is a u8, so 248 bytes (31 records) is the most one frame can declare.
        val records = List(31) { i -> (i * 2.5f - 5f) to i * 11_592L }
        val packet = weightFrame(records)
        assertEquals(250, packet.size)
        assertEquals(0xF8.toByte(), packet[1])
        assertEquals(records.map { (kg, us) -> Sample(kg.toDouble(), us) }, Progressor.parseWeightSamples(packet))
        assertEquals(31, Progressor.parseWeights(packet).size)
    }

    @Test
    fun skipsNonFiniteWeightsAndKeepsTheRest() {
        assertEquals(listOf(Sample(10.0, 4000), Sample(15.0, 5000)), Progressor.parseWeightSamples(p5))
        assertContentEquals(doubleArrayOf(10.0, 15.0), Progressor.parseWeights(p5))
        // A frame of only NaN is still a valid weight frame, just with nothing in it (P11).
        assertNoWeights(p11)
        assertEquals(Response.WEIGHT_MEASUREMENT, Progressor.responseOf(p11))
    }

    @Test
    fun deviceTimeIsUnsigned() {
        assertEquals(
            listOf(Sample(-2.0, 0), Sample(150.0, 4_294_967_295), Sample(-1.5, 2_147_483_648)),
            Progressor.parseWeightSamples(p6),
        )
    }

    @Test
    fun dropsTruncatedFrameWhole() {
        // P7 is P2 minus its last byte. Every shorter prefix yields nothing either.
        for (len in 0 until p2.size) {
            val prefix = p2.copyOf(len)
            assertNoWeights(prefix)
            assertNull(Progressor.responseOf(prefix), "prefix of $len bytes")
        }
    }

    @Test
    fun dropsFrameWhenDeclaredLengthExceedsPacket() {
        // Two full records on board, but N claims more. Unlike Force Board, nothing is salvaged.
        val claims3 = p2.copyOf().also { it[1] = 0x18 }
        val claims255 = p2.copyOf().also { it[1] = 0xFF.toByte() }
        assertNoWeights(claims3)
        assertNoWeights(claims255)
        assertNull(Progressor.responseOf(claims3))
        assertNull(Progressor.responseOf(claims255))
    }

    @Test
    fun dropsWeightFrameThatIsNotWholeRecords() {
        assertNoWeights(p8)
        assertNull(Progressor.responseOf(p8))
        // N = 9 with a full record plus one byte present.
        val n9 = p1 + bytes(0x00)
        n9[1] = 0x09
        assertNoWeights(n9)
        assertNull(Progressor.responseOf(n9))
    }

    @Test
    fun ignoresBytesAfterDeclaredPayload() {
        assertEquals(listOf(Sample(1.0, 1)), Progressor.parseWeightSamples(p9))
        // Even a complete second record is ignored when N only covers the first.
        val extraRecord = p2.copyOf().also { it[1] = 0x08 }
        assertEquals(listOf(Sample(10.0, 1000)), Progressor.parseWeightSamples(extraRecord))
        assertEquals(Response.WEIGHT_MEASUREMENT, Progressor.responseOf(extraRecord))
    }

    @Test
    fun emptyWeightFrameIsValid() {
        // P10: a weight frame with no records still counts as a weight frame.
        assertNoWeights(bytes(0x01, 0x00))
        assertEquals(Response.WEIGHT_MEASUREMENT, Progressor.responseOf(bytes(0x01, 0x00)))
    }

    @Test
    fun emptyOrShortPacketsYieldNothing() {
        // P12
        assertNoWeights(bytes())
        assertNoWeights(bytes(0x01))
        assertNull(Progressor.responseOf(bytes()))
        assertNull(Progressor.responseOf(bytes(0x01)))
    }

    @Test
    fun responseOfEveryCode() {
        assertEquals(listOf(0x00, 0x01, 0x02, 0x03, 0x04), Response.entries.map { it.code })
        assertEquals(Response.COMMAND, Progressor.responseOf(bytes(0x00, 0x00)))
        assertEquals(Response.COMMAND, Progressor.responseOf(p14))
        assertEquals(Response.WEIGHT_MEASUREMENT, Progressor.responseOf(p1))
        assertEquals(Response.RFD_PEAK, Progressor.responseOf(bytes(0x02, 0x00))) // P15
        assertEquals(Response.RFD_PEAK_SERIES, Progressor.responseOf(bytes(0x03, 0x04, 0x00, 0x00, 0x00, 0x00))) // P15
        assertEquals(Response.LOW_POWER_WARNING, Progressor.responseOf(bytes(0x04, 0x00)))
    }

    @Test
    fun unknownCodesAreIgnoredNotThrown() {
        // P16 (0x05, 0xFF) and everything in between. Upstream throws here.
        for (code in 0x05..0xFF) {
            assertNull(Progressor.responseOf(bytes(code, 0x00)), "code $code")
            assertNoWeights(bytes(code, 0x00))
            assertFalse(Progressor.isLowPowerWarning(bytes(code, 0x00)))
        }
    }

    @Test
    fun malformedNonWeightFramesHaveNoResponse() {
        assertNull(Progressor.responseOf(bytes(0x00, 0x04, 0x74, 0x0E, 0x00))) // one byte short
        assertNull(Progressor.responseOf(bytes(0x02, 0x01)))
        assertNull(Progressor.responseOf(bytes(0x00)))
    }

    @Test
    fun lowPowerWarningIgnoresLength() {
        // P13: byte 0 alone decides, even when N overruns the packet.
        for (packet in listOf(bytes(0x04, 0x00), bytes(0x04), bytes(0x04, 0x05), bytes(0x04, 0x00, 0xFF))) {
            assertTrue(Progressor.isLowPowerWarning(packet))
            assertEquals(Response.LOW_POWER_WARNING, Progressor.responseOf(packet))
            assertNoWeights(packet)
        }
        assertFalse(Progressor.isLowPowerWarning(bytes()))
        assertFalse(Progressor.isLowPowerWarning(p1))
        assertFalse(Progressor.isLowPowerWarning(bytes(0x00, 0x01, 0x04)))
        assertFalse(Progressor.isLowPowerWarning(bytes(0x40, 0x00)))
    }

    @Test
    fun nonWeightFramesYieldNoWeights() {
        assertNoWeights(p14) // P14
        assertNoWeights(bytes(0x02, 0x00)) // P15
        assertNoWeights(bytes(0x03, 0x04, 0x00, 0x00, 0x00, 0x00)) // P15
        // A command reply laid out exactly like P1's record is still not weight data.
        assertNoWeights(p1.copyOf().also { it[0] = 0x00 })
        assertNoWeights(p1.copyOf().also { it[0] = 0x02 })
    }

    @Test
    fun parseWeightsMatchesParseWeightSamples() {
        val packets = listOf(p1, p2, p3, p4, p5, p6, p8, p9, p11, p14, bytes(0x01, 0x00), bytes(0x04, 0x00))
        for (packet in packets) {
            assertContentEquals(
                Progressor.parseWeightSamples(packet).map { it.weight }.toDoubleArray(),
                Progressor.parseWeights(packet),
            )
        }
    }

    @Test
    fun commandBytes() {
        assertContentEquals(bytes(0x64), Command.TARE_SCALE.payload())
        assertContentEquals(bytes(0x65), Command.START_WEIGHT_MEAS.payload())
        assertContentEquals(bytes(0x66), Command.STOP_WEIGHT_MEAS.payload())
        assertContentEquals(bytes(0x67), Command.START_PEAK_RFD_MEAS.payload())
        assertContentEquals(bytes(0x68), Command.START_PEAK_RFD_MEAS_SERIES.payload())
        assertContentEquals(bytes(0x6B), Command.GET_FIRMWARE_VERSION.payload())
        assertContentEquals(bytes(0x6C), Command.GET_ERROR_INFORMATION.payload())
        assertContentEquals(bytes(0x6D), Command.CLR_ERROR_INFORMATION.payload())
        assertContentEquals(bytes(0x6E), Command.SLEEP.payload())
        assertContentEquals(bytes(0x6F), Command.GET_BATTERY_VOLTAGE.payload())
        assertContentEquals(bytes(0x70), Command.GET_PROGRESSOR_ID.payload())
        assertContentEquals(bytes(0x72), Command.GET_CALIBRATION.payload())
        assertTrue(Command.entries.all { it.payload().contentEquals(bytes(it.opcode.toInt())) })
    }

    @Test
    fun calibrationWritesAndRebootAreNotExposed() {
        val opcodes = Command.entries.map { it.opcode.toInt() }
        for (forbidden in listOf(0x69, 0x6A, 0x71, 0x73, 0x74, 0x75, 0x76)) {
            assertFalse(forbidden in opcodes, "opcode $forbidden")
        }
    }

    @Test
    fun batteryMillivolts() {
        assertEquals(3700L, Progressor.parseBatteryMillivolts(p14))
        assertEquals(3224L, Progressor.parseBatteryMillivolts(bytes(0x00, 0x04, 0x98, 0x0C, 0x00, 0x00))) // real unit
        assertEquals(4_294_967_295L, Progressor.parseBatteryMillivolts(bytes(0x00, 0x04, 0xFF, 0xFF, 0xFF, 0xFF)))
        // Extra payload bytes are ignored, as upstream does.
        assertEquals(3700L, Progressor.parseBatteryMillivolts(bytes(0x00, 0x05, 0x74, 0x0E, 0x00, 0x00, 0xAA)))
    }

    @Test
    fun batteryRejectsWrongLengthOrKind() {
        assertNull(Progressor.parseBatteryMillivolts(bytes(0x00, 0x03, 0x74, 0x0E, 0x00))) // payload too short
        assertNull(Progressor.parseBatteryMillivolts(bytes(0x00, 0x00)))
        assertNull(Progressor.parseBatteryMillivolts(bytes(0x00, 0x04, 0x74, 0x0E, 0x00))) // truncated frame
        assertNull(Progressor.parseBatteryMillivolts(bytes()))
        assertNull(Progressor.parseBatteryMillivolts(p14.copyOf().also { it[0] = 0x01 }))
        assertNull(Progressor.parseBatteryMillivolts(p14.copyOf().also { it[0] = 0x04 }))
        assertNull(Progressor.parseBatteryMillivolts(p1))
    }

    @Test
    fun matchesAdvertisement() {
        val apple = listOf("7E4E1701-1EA6-40C9-9DCC-13D34FFEAD57")
        val android = listOf("7e4e1701-1ea6-40c9-9dcc-13d34ffead57")
        assertTrue(Progressor.matchesAdvertisement("Progressor_4760", emptyList()))
        assertTrue(Progressor.matchesAdvertisement("progressor_xxxx", emptyList()))
        assertTrue(Progressor.matchesAdvertisement("PROGRESSOR", emptyList()))
        assertTrue(Progressor.matchesAdvertisement("Progressor", emptyList()))
        assertTrue(Progressor.matchesAdvertisement(null, apple))
        assertTrue(Progressor.matchesAdvertisement(null, android))
        assertFalse(Progressor.matchesAdvertisement("dfu_target_4760", listOf("0000fe59-0000-1000-8000-00805f9b34fb")))
        assertFalse(Progressor.matchesAdvertisement("Force Board", emptyList()))
        assertFalse(Progressor.matchesAdvertisement("My Progressor", emptyList())) // prefix only
        assertFalse(Progressor.matchesAdvertisement("Prog", emptyList()))
        assertFalse(Progressor.matchesAdvertisement("", emptyList()))
        assertFalse(Progressor.matchesAdvertisement(null, emptyList()))
    }

    @Test
    fun oneNotificationSharesArrivalTime() {
        // Spec 10.4: every sample in a notification is ingested with that notification's time.
        assertEquals(ForceUnit.KG, Progressor.STREAM_UNIT)
        val s = ForceSession(Progressor.STREAM_UNIT)
        val t = 1_700_000_000_000L
        val m = Progressor.parseWeights(p2).map { s.ingest(it, t) }
        assertEquals(listOf(10.0, 20.0), m.map { it.current })
        assertEquals(20.0, m.last().peak)
        assertEquals(15.0, m.last().mean, 1e-12)
        assertEquals(listOf(t, t), m.map { it.timestampMs })
    }

    @Test
    fun sessionStatsSkipNonFinite() {
        // Upstream test on P5: mean 12.5, peak 15, min 10.
        val s = ForceSession(Progressor.STREAM_UNIT)
        val m = Progressor.parseWeights(p5).map { s.ingest(it, 0) }.last()
        assertEquals(12.5, m.mean, 1e-12)
        assertEquals(15.0, m.peak)
        assertEquals(10.0, m.min)
        assertEquals(2, m.sampleIndex)
    }

    @Test
    fun displaysInNewtons() {
        val s = ForceSession(Progressor.STREAM_UNIT, displayUnit = ForceUnit.N)
        assertEquals(122.583125, s.ingest(Progressor.parseWeights(p1)[0], 0).current, 1e-9)
    }
}
