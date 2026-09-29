/*
 * Protocol ported from hangtime-grip-connect
 * (packages/core/src/models/device/wh-c06.model.ts)
 * Copyright (c) 2024, Stevie-Ray Hartog. BSD 2-Clause License. See NOTICE.md.
 *
 * Signed weight decoding and the Apple offset (+2) cross-checked against
 * sebws/Crane (Crane/Core/Bluetooth/Devices/WHC06.swift), MIT License.
 */
package com.venturilogic.grip.devices

import com.venturilogic.grip.ForceUnit

/**
 * Weiheng WH-C06 / MAT Muscle Meter. There is no GATT connection: the scale
 * broadcasts weight inside BLE advertisement manufacturer data, so "connecting"
 * means scanning continuously (duplicates allowed) and parsing every packet.
 */
object WhC06 {
    /** Bluetooth SIG company identifier in the advertisement (0x0100). */
    const val COMPANY_ID = 0x0100

    /** Local name the scale advertises. */
    const val ADVERTISED_NAME = "IF_B7"

    val STREAM_UNIT = ForceUnit.KG

    /** Treat the scale as gone after this long without an advertisement (upstream: 10 s). */
    const val ADVERTISEMENT_TIMEOUT_MS = 10_000L

    // Offsets into the manufacturer payload *after* the 2-byte company id.
    private const val WEIGHT_OFFSET = 10
    private const val STATUS_OFFSET = 14

    /**
     * Where the manufacturer payload came from. Android's
     * `ScanRecord.getManufacturerSpecificData(id)` strips the company id;
     * CoreBluetooth's `CBAdvertisementDataManufacturerDataKey` keeps it.
     */
    enum class PayloadSource(internal val shift: Int) {
        /** Company id already removed (Android, Web Bluetooth). */
        WITHOUT_COMPANY_ID(0),

        /** Raw bytes starting with the little-endian company id (Apple). */
        WITH_COMPANY_ID(2),
    }

    data class Reading(
        /** Weight in [STREAM_UNIT], before software tare. */
        val weight: Double,
        /** High nibble of the status byte, if present. Believed to mean "stable". Unverified. */
        val stableFlag: Int?,
        /** Low nibble of the status byte, if present. Believed to be the display unit. Unverified. */
        val unitFlag: Int?,
    )

    /**
     * Parse one advertisement's manufacturer data. Returns null if the payload is too
     * short, or (for [PayloadSource.WITH_COMPANY_ID]) the company id doesn't match.
     *
     * Weight is a big-endian *signed* 16-bit value in hundredths. Upstream reads it
     * unsigned; Crane reads it signed. Signed is kept so a scale tared under load
     * reports small negatives instead of ~655 kg.
     */
    fun parse(payload: ByteArray, source: PayloadSource): Reading? {
        if (source == PayloadSource.WITH_COMPANY_ID) {
            if (payload.size < 2) return null
            val id = payload.u8(0) or (payload.u8(1) shl 8)
            if (id != COMPANY_ID) return null
        }
        val w = WEIGHT_OFFSET + source.shift
        if (payload.size < w + 2) return null
        val raw = ((payload.u8(w) shl 8) or payload.u8(w + 1)).toShort().toInt()

        val s = STATUS_OFFSET + source.shift
        val status = if (payload.size > s) payload.u8(s) else null
        return Reading(
            weight = raw / 100.0,
            stableFlag = status?.let { (it and 0xF0) shr 4 },
            unitFlag = status?.let { it and 0x0F },
        )
    }

    /** True once the scale has been silent for longer than [ADVERTISEMENT_TIMEOUT_MS]. */
    fun isStale(lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs > ADVERTISEMENT_TIMEOUT_MS
}
