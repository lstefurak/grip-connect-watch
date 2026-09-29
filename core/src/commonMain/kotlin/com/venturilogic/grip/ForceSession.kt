/*
 * Session math ported from hangtime-grip-connect
 * (packages/core/src/models/device.model.ts: applyTare, activityCheck, stats)
 * Copyright (c) 2024, Stevie-Ray Hartog. BSD 2-Clause License. See NOTICE.md.
 *
 * Differences from upstream, deliberate:
 *  - No timers. Tare and activity debounce are evaluated against sample timestamps,
 *    so the class is pure, deterministic, and identical on Wear OS and watchOS.
 *  - ActivityConfig.threshold is in the *display* unit, not the device's native unit.
 */
package com.venturilogic.grip

/**
 * Debounced activity detection: [isActive][ForceMeasurement.isActive] flips only after
 * the force has stayed on the other side of [threshold] for [durationMs].
 */
data class ActivityConfig(
    val threshold: Double = 2.5,
    val durationMs: Long = 1_000,
)

/**
 * Turns raw device samples into [ForceMeasurement]s: software tare, peak/mean/min,
 * sample rate, and activity detection. Platform BLE code feeds it; UI reads from it.
 *
 * Not thread-safe: call from a single thread/queue.
 */
class ForceSession(
    /** Unit the device reports in (Force Board: LBS, WH-C06: KG). */
    val streamUnit: ForceUnit,
    /** Unit for emitted measurements. */
    var displayUnit: ForceUnit = ForceUnit.KG,
    var activity: ActivityConfig = ActivityConfig(),
) {
    private var peak = Double.NEGATIVE_INFINITY
    private var min = Double.POSITIVE_INFINITY
    private var sum = 0.0
    private var count = 0L

    private var tareOffset = 0.0
    private var tareStartMs: Long? = null
    private var tareDurationMs = 0L
    private val tareSamples = ArrayList<Double>()

    private var active = false
    private var pendingTarget: Boolean? = null
    private var pendingSinceMs = 0L

    private val recentTimestamps = ArrayDeque<Long>()
    private var firstTimestampMs: Long? = null

    val isTaring: Boolean get() = tareStartMs != null

    /** Current software tare offset, in [streamUnit]. */
    val tareOffsetNative: Double get() = tareOffset

    /**
     * Start a software tare: samples received during the next [durationMs] are
     * averaged and subtracted from everything after. Returns false if one is running.
     */
    fun tare(nowMs: Long, durationMs: Long = 5_000): Boolean {
        if (isTaring) return false
        tareStartMs = nowMs
        tareDurationMs = durationMs
        tareSamples.clear()
        return true
    }

    /** Drop any software tare, e.g. after the device performed a hardware tare. */
    fun clearTare() {
        tareOffset = 0.0
        tareStartMs = null
        tareSamples.clear()
    }

    /** Reset statistics for a new set/rep. Keeps the tare. */
    fun resetStats() {
        peak = Double.NEGATIVE_INFINITY
        min = Double.POSITIVE_INFINITY
        sum = 0.0
        count = 0
        recentTimestamps.clear()
        firstTimestampMs = null
        active = false
        pendingTarget = null
    }

    /** Ingest one raw sample in [streamUnit]. */
    fun ingest(raw: Double, timestampMs: Long): ForceMeasurement {
        applyTare(raw, timestampMs)
        val adjusted = raw - tareOffset
        val clamped = maxOf(FLOOR, adjusted)

        peak = maxOf(peak, adjusted)
        min = minOf(min, clamped)
        sum += clamped
        count++

        val display = { v: Double -> streamUnit.convert(v, displayUnit) }
        val current = display(clamped)
        updateActivity(current, timestampMs)

        return ForceMeasurement(
            unit = displayUnit,
            timestampMs = timestampMs,
            current = current,
            peak = display(peak),
            mean = display(sum / count),
            min = display(min),
            sampleIndex = count,
            samplingRateHz = sampleRate(timestampMs),
            isActive = active,
            isTaring = isTaring,
        )
    }

    private fun applyTare(sample: Double, nowMs: Long) {
        val start = tareStartMs ?: return
        tareSamples.add(sample)
        if (nowMs - start >= tareDurationMs) {
            tareOffset = tareSamples.average()
            tareStartMs = null
            tareSamples.clear()
        }
    }

    private fun updateActivity(value: Double, nowMs: Long) {
        val activeNow = value > activity.threshold
        if (activeNow == active) {
            pendingTarget = null
            return
        }
        if (pendingTarget != activeNow) {
            pendingTarget = activeNow
            pendingSinceMs = nowMs
        }
        if (nowMs - pendingSinceMs >= activity.durationMs) {
            active = activeNow
            pendingTarget = null
        }
    }

    private fun sampleRate(nowMs: Long): Int? {
        if (firstTimestampMs == null) firstTimestampMs = nowMs
        recentTimestamps.addLast(nowMs)
        while (recentTimestamps.isNotEmpty() && nowMs - recentTimestamps.first() > 1_000) {
            recentTimestamps.removeFirst()
        }
        return if (nowMs - firstTimestampMs!! >= 1_000) recentTimestamps.size else null
    }

    private companion object {
        /** Upstream clamps readings at -1000 native units. */
        const val FLOOR = -1000.0
    }
}
