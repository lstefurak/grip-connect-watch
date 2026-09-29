package com.venturilogic.grip.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.venturilogic.grip.ForceMeasurement
import com.venturilogic.grip.ForceSession
import com.venturilogic.grip.ForceUnit
import com.venturilogic.grip.wear.ble.AndroidForceBoard
import com.venturilogic.grip.wear.ble.AndroidWhC06
import com.venturilogic.grip.wear.ble.ForceSource
import com.venturilogic.grip.wear.ble.LinkState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class DeviceKind(val label: String) { FORCE_BOARD("Force Board"), WH_C06("WH-C06") }

/**
 * Glue: platform ForceSource -> shared ForceSession -> UI state.
 * The watchOS app has the same shape in Swift (WatchViewModel.swift).
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val _device = MutableStateFlow<DeviceKind?>(null)
    val device: StateFlow<DeviceKind?> = _device

    private val _measurement = MutableStateFlow<ForceMeasurement?>(null)
    val measurement: StateFlow<ForceMeasurement?> = _measurement

    private val _unit = MutableStateFlow(ForceUnit.KG)
    val unit: StateFlow<ForceUnit> = _unit

    val linkState = MutableStateFlow(LinkState.IDLE)
    val error = MutableStateFlow<String?>(null)

    private var source: ForceSource? = null
    private var session: ForceSession? = null
    private var jobs = emptyList<Job>()

    fun connect(kind: DeviceKind) {
        disconnect()
        val src = when (kind) {
            DeviceKind.FORCE_BOARD -> AndroidForceBoard(getApplication(), viewModelScope)
            DeviceKind.WH_C06 -> AndroidWhC06(getApplication(), viewModelScope)
        }
        val s = ForceSession(streamUnit = src.streamUnit, displayUnit = _unit.value)
        source = src
        session = s
        _device.value = kind
        _measurement.value = null
        jobs = listOf(
            viewModelScope.launch { src.samples.collect { _measurement.value = s.ingest(it.value, it.timestampMs) } },
            viewModelScope.launch { src.state.collect { linkState.value = it } },
            viewModelScope.launch { src.error.collect { error.value = it } },
        )
        src.start()
    }

    fun disconnect() {
        source?.stop()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        source = null
        session = null
        _device.value = null
        linkState.value = LinkState.IDLE
    }

    fun tare() {
        val s = session ?: return
        if (source?.hardwareTare() == true) {
            s.clearTare()
        } else {
            s.tare(System.currentTimeMillis(), SOFTWARE_TARE_MS)
        }
        s.resetStats()
    }

    fun resetStats() {
        session?.resetStats()
    }

    fun cycleUnit() {
        val next = when (_unit.value) {
            ForceUnit.KG -> ForceUnit.LBS
            ForceUnit.LBS -> ForceUnit.N
            ForceUnit.N -> ForceUnit.KG
        }
        _unit.value = next
        session?.displayUnit = next
    }

    override fun onCleared() = disconnect()

    private companion object {
        /** Shorter than upstream's 5 s default: you're standing at the board waiting. */
        const val SOFTWARE_TARE_MS = 2_000L
    }
}
