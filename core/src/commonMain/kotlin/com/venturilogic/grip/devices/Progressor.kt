/*
 * Protocol ported from hangtime-grip-connect
 * (packages/core/src/models/device/progressor.model.ts)
 * Copyright (c) 2024, Stevie-Ray Hartog. BSD 2-Clause License. See NOTICE.md.
 *
 * Primary source: Tindeq "Progressor API" (Bluetooth interface and example client)
 * https://github.com/blims/Tindeq-Progressor-API
 */
package com.venturilogic.grip.devices

import com.venturilogic.grip.ForceUnit

/**
 * Tindeq Progressor. GATT device: subscribe to [DATA_POINT], then write a [Command]
 * byte to [CONTROL_POINT] to start/stop streaming. Samples arrive in float kg, about
 * 80 Hz, several per notification. All multi-byte fields are little-endian.
 *
 * The unit also exposes Nordic Secure DFU (0xFE59). This app never touches it.
 */
object Progressor {
    /** Advertised local name prefix ("Progressor_4760" on a real unit). Match ignoring case. */
    const val NAME_PREFIX = "Progressor"

    val STREAM_UNIT = ForceUnit.KG

    // --- Service ----------------------------------------------------------------
    const val SERVICE = "7e4e1701-1ea6-40c9-9dcc-13d34ffead57"

    // --- Characteristics --------------------------------------------------------
    /** Notify: every [Response] frame, weight data included. Service [SERVICE]. */
    const val DATA_POINT = "7e4e1702-1ea6-40c9-9dcc-13d34ffead57"

    /** Write a [Command] byte. Service [SERVICE]. */
    const val CONTROL_POINT = "7e4e1703-1ea6-40c9-9dcc-13d34ffead57"

    /** Standard Client Characteristic Configuration descriptor. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /**
     * Opcodes written to [CONTROL_POINT]. Always sent as the single opcode byte, as
     * upstream and Tindeq's example client do. Real units also accept the TLV form
     * `[op, 0x00]`, but some clones only take one byte. Commands have no direct reply;
     * the GET_* ones answer on [DATA_POINT] with a [Response.COMMAND] frame.
     *
     * Left out on purpose so they can't be sent by accident: the calibration writes
     * (0x69 add point, 0x6A save, 0x71 set, 0x73 add table point, 0x76 clear table),
     * 0x74 (a calibration reset on Crimpdeq clones) and 0x75 reboot.
     */
    enum class Command(val opcode: Byte) {
        /** Zero the scale. Real units stop measuring after a tare; resend [START_WEIGHT_MEAS]. */
        TARE_SCALE(0x64),

        /** Stream [Response.WEIGHT_MEASUREMENT] frames. */
        START_WEIGHT_MEAS(0x65),

        /** Stop streaming. */
        STOP_WEIGHT_MEAS(0x66),

        /** Peak rate of force development. Reply layout undocumented. */
        START_PEAK_RFD_MEAS(0x67),

        /** Peak rate of force development, series. Reply layout undocumented. */
        START_PEAK_RFD_MEAS_SERIES(0x68),

        /** Reply: firmware version as UTF-8 text, e.g. "2.0.4". */
        GET_FIRMWARE_VERSION(0x6B),

        /** Reply: crash log as UTF-8 text, possibly empty. */
        GET_ERROR_INFORMATION(0x6C),

        /** Clear the crash log. */
        CLR_ERROR_INFORMATION(0x6D),

        /** Power off. Waking the unit again needs a button press. */
        SLEEP(0x6E),

        /** Reply: see [parseBatteryMillivolts]. Tindeq: stop streaming first. */
        GET_BATTERY_VOLTAGE(0x6F),

        /** Reply: device id. Format unverified. */
        GET_PROGRESSOR_ID(0x70),

        /** Reply: 12-byte calibration block. Layout unverified on real units. */
        GET_CALIBRATION(0x72);

        fun payload(): ByteArray = byteArrayOf(opcode)
    }

    /** Response codes: byte 0 of every [DATA_POINT] notification. */
    enum class Response(val code: Int) {
        /** Reply to the last GET_* [Command]. It carries no opcode, so the sender must know. */
        COMMAND(0x00),

        /** Weight records, see [parseWeightSamples]. */
        WEIGHT_MEASUREMENT(0x01),

        /** Peak RFD result. Layout undocumented; unsupported, as upstream. */
        RFD_PEAK(0x02),

        /** Peak RFD series result. Layout undocumented; unsupported, as upstream. */
        RFD_PEAK_SERIES(0x03),

        /** Battery nearly empty. Tindeq: the unit switches itself off after sending it. */
        LOW_POWER_WARNING(0x04),
    }

    data class Sample(
        /** Weight in [STREAM_UNIT], before software tare. */
        val weight: Double,
        /**
         * Device clock in microseconds since [Command.START_WEIGHT_MEAS], decoded unsigned.
         * Wraps after ~71.6 min. Diagnostics only: samples are timed by arrival.
         */
        val deviceTimeUs: Long,
    )

    /**
     * Response code of one [DATA_POINT] notification, or null if the frame is malformed
     * or the code is unknown. Upstream throws on unknown codes (Crimpdeq clones send 0x05
     * and 0x06); they are ignored here.
     *
     * Frame: byte 0 = code, byte 1 = payload length N, then N payload bytes. Anything
     * after 2 + N is ignored. Malformed means fewer than 2 + N bytes, or a weight frame
     * whose N is not a multiple of 8; upstream drops both whole. [Response.LOW_POWER_WARNING]
     * is recognised from byte 0 alone, whatever the length.
     */
    fun responseOf(packet: ByteArray): Response? {
        if (packet.isEmpty()) return null
        val code = packet.u8(0)
        if (code == Response.LOW_POWER_WARNING.code) return Response.LOW_POWER_WARNING
        if (packet.size < 2) return null
        val n = packet.u8(1)
        if (packet.size < 2 + n) return null
        if (code == Response.WEIGHT_MEASUREMENT.code && n % 8 != 0) return null
        return Response.entries.firstOrNull { it.code == code }
    }

    /**
     * Decode a [Response.WEIGHT_MEASUREMENT] notification into samples (kg, before tare),
     * in record order. Anything else, including a malformed frame, yields an empty list.
     *
     * Payload: N / 8 records of float32 kg + uint32 µs, record i at offset 2 + 8i. The count
     * per notification varies with firmware and MTU (2 at the default MTU, 15 seen on a real
     * unit, at most 31), so never assume one. Non-finite weights (NaN, ±Inf) are skipped one
     * by one and the rest of the frame is kept, as upstream does.
     */
    fun parseWeightSamples(packet: ByteArray): List<Sample> {
        if (responseOf(packet) != Response.WEIGHT_MEASUREMENT) return emptyList()
        val records = packet.u8(1) / 8
        val samples = ArrayList<Sample>(records)
        for (i in 0 until records) {
            val o = 2 + i * 8
            val kg = Float.fromBits(packet.u32le(o).toInt())
            if (kg.isFinite()) samples.add(Sample(kg.toDouble(), packet.u32le(o + 4)))
        }
        return samples
    }

    /** Just the weights from [parseWeightSamples]. Empty for anything but a valid weight frame. */
    fun parseWeights(packet: ByteArray): DoubleArray {
        val samples = parseWeightSamples(packet)
        return DoubleArray(samples.size) { samples[it].weight }
    }

    /** True for a [Response.LOW_POWER_WARNING] notification, whatever its length. */
    fun isLowPowerWarning(packet: ByteArray): Boolean = responseOf(packet) == Response.LOW_POWER_WARNING

    /**
     * Decode the [Response.COMMAND] reply to [Command.GET_BATTERY_VOLTAGE]: uint32 millivolts
     * at the start of the payload, e.g. 00 04 74 0E 00 00 -> 3700. Extra payload bytes are
     * ignored, as upstream does. Null for any other frame or a payload under 4 bytes.
     *
     * Replies carry no opcode: only call this when the last command sent was GET_BATTERY_VOLTAGE.
     */
    fun parseBatteryMillivolts(packet: ByteArray): Long? {
        if (responseOf(packet) != Response.COMMAND || packet.u8(1) < 4) return null
        return packet.u32le(2)
    }

    /**
     * True if a scan result is a Progressor: the advertised local name starts with
     * [NAME_PREFIX], ignoring case, or the advertised service UUIDs include [SERVICE].
     * The name is the main signal: the 128-bit UUID most likely rides only in the scan
     * response, which is why OS-level UUID filters miss the unit. A unit stuck in its DFU
     * bootloader advertises "dfu_target_xxxx" and does not match.
     */
    fun matchesAdvertisement(localName: String?, serviceUuids: List<String>): Boolean =
        (localName != null && localName.startsWith(NAME_PREFIX, ignoreCase = true)) ||
            serviceUuids.any { it.equals(SERVICE, ignoreCase = true) }
}

/** Little-endian unsigned 32-bit value at [i], widened to Long so it stays unsigned. */
private fun ByteArray.u32le(i: Int): Long =
    u8(i).toLong() or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)
