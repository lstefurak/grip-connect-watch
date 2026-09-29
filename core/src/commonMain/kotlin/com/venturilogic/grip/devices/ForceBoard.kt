/*
 * Protocol ported from hangtime-grip-connect
 * (packages/core/src/models/device/forceboard.model.ts)
 * Copyright (c) 2024, Stevie-Ray Hartog. BSD 2-Clause License. See NOTICE.md.
 *
 * Primary source: PitchSix "Force Board Portable Public API 1.0"
 * https://pitchsix.com/pages/downloads
 */
package com.venturilogic.grip.devices

import com.venturilogic.grip.ForceUnit

/**
 * PitchSix Force Board. GATT device: subscribe to [FORCE_DATA], then write a mode
 * byte to [DEVICE_MODE] to start/stop streaming. Samples arrive in integer lbs.
 */
object ForceBoard {
    /** Exact advertised local name to scan for. */
    const val ADVERTISED_NAME = "Force Board"

    val STREAM_UNIT = ForceUnit.LBS

    // --- Services ---------------------------------------------------------------
    const val FORCEBOARD_SERVICE = "9a88d67f-8df2-4afe-9e0d-c2bbbe773dd0"
    const val WEIGHT_SERVICE = "467a8516-6e39-11eb-9439-0242ac130002"
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
    const val HUMIDITY_SERVICE = "cf194c6f-d0c1-47b2-aeff-dc610f09bd18"
    const val TEMPERATURE_SERVICE = "3a90328c-c266-4c76-b05a-6af6104a0b13"

    // --- Characteristics --------------------------------------------------------
    /** Notify: force samples. Service [FORCEBOARD_SERVICE]. */
    const val FORCE_DATA = "9a88d682-8df2-4afe-9e0d-c2bbbe773dd0"

    /** Write [TARE_PAYLOAD] to zero the board. Service [FORCEBOARD_SERVICE]. */
    const val TARE = "9a88d683-8df2-4afe-9e0d-c2bbbe773dd0"

    /** Write 3-byte lbs threshold for Quick Start mode. Service [FORCEBOARD_SERVICE]. */
    const val THRESHOLD = "9a88d686-8df2-4afe-9e0d-c2bbbe773dd0"

    /** Write a [Mode] byte. Service [WEIGHT_SERVICE]. */
    const val DEVICE_MODE = "467a8517-6e39-11eb-9439-0242ac130002"

    /** Read: battery percent (standard 0x2A19). Service [BATTERY_SERVICE]. */
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    /** Read: manufacturer name string. Service [DEVICE_INFO_SERVICE]. */
    const val MANUFACTURER_NAME = "00002a29-0000-1000-8000-00805f9b34fb"

    const val HUMIDITY_LEVEL = "cf194c70-d0c1-47b2-aeff-dc610f09bd18"
    const val TEMPERATURE_LEVEL = "3a90328d-c266-4c76-b05a-6af6104a0b13"

    /** Standard Client Characteristic Configuration descriptor. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** Device Mode values written to [DEVICE_MODE]. */
    enum class Mode(val byte: Byte) {
        /** Continuously stream force data. */
        STREAM(0x04),

        /** Run the board's own tare routine. */
        TARE(0x05),

        /** Stream only while force exceeds the [THRESHOLD]. */
        QUICK_START(0x06),

        /** Stop streaming. */
        IDLE(0x07);

        fun payload(): ByteArray = byteArrayOf(byte)
    }

    val TARE_PAYLOAD: ByteArray = byteArrayOf(0x01)

    /** Max samples per notification per the public API. */
    const val MAX_SAMPLES_PER_PACKET = 6

    /**
     * Decode a [FORCE_DATA] notification into raw samples (integer lbs, before tare).
     *
     * Layout: bytes 0-1 = sample count (big-endian), then 3 bytes per sample.
     * Sample = b0 * 32768 + b1 * 256 + b2. The 32768 multiplier (not 65536) is what
     * the PitchSix public API specifies and what upstream implements; in practice b0 is
     * zero for any real load, so it only matters above ~65,000 lbs.
     *
     * Truncated trailing samples are dropped rather than throwing.
     */
    fun parseForceData(packet: ByteArray): IntArray {
        if (packet.size < 2) return IntArray(0)
        val declared = (packet.u8(0) shl 8) or packet.u8(1)
        val available = (packet.size - 2) / 3
        val n = minOf(declared, available)
        return IntArray(n) { i ->
            val o = 2 + i * 3
            packet.u8(o) * 32768 + packet.u8(o + 1) * 256 + packet.u8(o + 2)
        }
    }

    /**
     * Encode a Quick Start threshold (whole lbs, 0..0xFFFFFF) as 3 big-endian bytes,
     * e.g. 25 lbs -> 00 00 19.
     */
    fun thresholdPayload(lbs: Int): ByteArray {
        require(lbs in 0..0xFFFFFF) { "threshold out of range: $lbs" }
        return byteArrayOf((lbs shr 16).toByte(), (lbs shr 8).toByte(), lbs.toByte())
    }

    /** Battery Level characteristic is a single unsigned byte, 0-100. */
    fun parseBatteryPercent(value: ByteArray): Int? = if (value.isEmpty()) null else value.u8(0)
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
