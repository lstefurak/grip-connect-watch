package com.venturilogic.grip.wear.ble

import com.venturilogic.grip.ForceUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class LinkState { IDLE, SCANNING, CONNECTING, STREAMING, DISCONNECTED, ERROR }

/** A raw sample in the device's native unit, before tare. */
data class RawSample(val value: Double, val timestampMs: Long)

/**
 * The thin platform layer: gets bytes off the radio and through the shared core parsers.
 * Everything above this (stats, tare math, units, activity) lives in :core.
 */
interface ForceSource {
    val name: String
    val streamUnit: ForceUnit
    val state: StateFlow<LinkState>
    val error: StateFlow<String?>
    val samples: Flow<RawSample>

    /** Scan, connect if needed, and begin streaming. Returns immediately; watch [state]. */
    fun start()

    /** Stop streaming and release the radio. */
    fun stop()

    /**
     * Hardware tare if the device has one. Returns true if handled, false if the caller
     * should fall back to a software tare in the core ForceSession.
     */
    fun hardwareTare(): Boolean
}
