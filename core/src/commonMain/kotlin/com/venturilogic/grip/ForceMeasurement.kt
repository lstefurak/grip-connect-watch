/*
 * Shape mirrors ForceMeasurement in hangtime-grip-connect
 * (packages/core/src/interfaces/callback.interface.ts)
 * Copyright (c) 2024, Stevie-Ray Hartog. BSD 2-Clause License. See NOTICE.md.
 */
package com.venturilogic.grip

/**
 * One processed sample plus running session statistics.
 * All force values are in [unit].
 */
data class ForceMeasurement(
    val unit: ForceUnit,
    /** Unix epoch milliseconds when the sample was received. */
    val timestampMs: Long,
    /** Instantaneous tare-adjusted force. */
    val current: Double,
    /** Highest force seen this session. */
    val peak: Double,
    /** Mean force across the session. */
    val mean: Double,
    /** Lowest force seen this session. */
    val min: Double,
    /** 1-based sample counter for this session. */
    val sampleIndex: Long,
    /** Samples received in the last second, once a full second of data exists. */
    val samplingRateHz: Int?,
    /** Debounced "someone is pulling" state (see [ActivityConfig]). */
    val isActive: Boolean,
    /** True while a software tare is collecting samples. */
    val isTaring: Boolean,
)
