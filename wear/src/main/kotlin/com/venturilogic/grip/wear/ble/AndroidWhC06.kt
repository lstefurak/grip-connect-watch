package com.venturilogic.grip.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.venturilogic.grip.ForceUnit
import com.venturilogic.grip.devices.WhC06
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Weiheng WH-C06. No connection: we scan continuously and parse the weight out of every
 * advertisement's manufacturer data. The first scale heard is locked in by MAC address
 * so a second scale in the gym doesn't interleave readings.
 *
 * This is the device the web version can't do without a Chrome flag; native Android
 * reads advertisement data directly.
 */
@SuppressLint("MissingPermission")
class AndroidWhC06(
    context: Context,
    private val scope: CoroutineScope,
) : ForceSource {
    override val name = "WH-C06"
    override val streamUnit: ForceUnit = WhC06.STREAM_UNIT

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter

    private val _state = MutableStateFlow(LinkState.IDLE)
    override val state: StateFlow<LinkState> = _state
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error
    private val _samples = MutableSharedFlow<RawSample>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: SharedFlow<RawSample> = _samples

    @Volatile private var lockedAddress: String? = null
    @Volatile private var lastSeenMs = 0L
    private var watchdog: Job? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val payload = result.scanRecord?.getManufacturerSpecificData(WhC06.COMPANY_ID) ?: return
            val address = result.device.address
            if (lockedAddress == null) lockedAddress = address
            if (address != lockedAddress) return

            // Android strips the 2-byte company id.
            val reading = WhC06.parse(payload, WhC06.PayloadSource.WITHOUT_COMPANY_ID) ?: return
            val now = System.currentTimeMillis()
            lastSeenMs = now
            _state.value = LinkState.STREAMING
            _samples.tryEmit(RawSample(reading.weight, now))
        }

        override fun onScanFailed(errorCode: Int) {
            _error.value = when (errorCode) {
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "Scan throttled by Android. Wait 30 s and try again."
                else -> "Scan failed ($errorCode)"
            }
            _state.value = LinkState.ERROR
        }
    }

    override fun start() {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _error.value = "Bluetooth is off"
            _state.value = LinkState.ERROR
            return
        }
        _error.value = null
        lockedAddress = null
        lastSeenMs = System.currentTimeMillis()
        _state.value = LinkState.SCANNING

        // Empty data + empty mask = "has manufacturer data for this company id".
        // A filter is also required for scans to keep running with the screen off.
        val filters = listOf(
            ScanFilter.Builder().setManufacturerData(WhC06.COMPANY_ID, ByteArray(0), ByteArray(0)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        scanner.startScan(filters, settings, callback)

        watchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                if (WhC06.isStale(lastSeenMs, System.currentTimeMillis())) {
                    _state.value = if (lockedAddress == null) LinkState.SCANNING else LinkState.DISCONNECTED
                }
            }
        }
    }

    override fun stop() {
        watchdog?.cancel()
        adapter?.bluetoothLeScanner?.stopScan(callback)
        _state.value = LinkState.IDLE
    }

    /** The WH-C06 has a physical tare button but no BLE command; use a software tare. */
    override fun hardwareTare(): Boolean = false
}
