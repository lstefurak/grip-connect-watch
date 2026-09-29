/*
 * Portions ported from hangtime-grip-connect (packages/core/src/utils.ts)
 * Copyright (c) 2024, Stevie-Ray Hartog. BSD 2-Clause License. See NOTICE.md.
 */
package com.venturilogic.grip

/** Force-equivalent units. kg and lbs mean kgf / lbf. */
enum class ForceUnit(val symbol: String) {
    KG("kg"),
    LBS("lbs"),
    N("N");

    fun toNewtons(value: Double): Double = when (this) {
        KG -> value * KG_TO_N
        LBS -> value * LBS_TO_N
        N -> value
    }

    fun fromNewtons(value: Double): Double = when (this) {
        KG -> value / KG_TO_N
        LBS -> value / LBS_TO_N
        N -> value
    }

    /** Convert [value] expressed in this unit into [to]. */
    fun convert(value: Double, to: ForceUnit): Double =
        if (this == to) value else to.fromNewtons(toNewtons(value))

    companion object {
        /** 1 kgf in newtons (standard gravity). */
        const val KG_TO_N = 9.80665

        /** 1 lbf in newtons. */
        const val LBS_TO_N = 4.4482216152605
    }
}
