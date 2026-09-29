package com.venturilogic.grip.wear.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.venturilogic.grip.ForceUnit
import com.venturilogic.grip.devices.ForceBoard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * PitchSix Force Board over GATT.
 *
 * Android GATT allows one outstanding operation at a time, so every write goes through
 * [gattOp], which holds a mutex until the matching callback fires.
 *
 * Permissions (BLUETOOTH_SCAN / BLUETOOTH_CONNECT) are requested by MainActivity before
 * [start] is ever called, hence the @SuppressLint.
 */
@SuppressLint("MissingPermission")
class AndroidForceBoard(
    context: Context,
    private val scope: CoroutineScope,
) : ForceSource {
    override val name = "Force Board"
    override val streamUnit: ForceUnit = ForceBoard.STREAM_UNIT

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(LinkState.IDLE)
    override val state: StateFlow<LinkState> = _state
    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error
    private val _samples = MutableSharedFlow<RawSample>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: SharedFlow<RawSample> = _samples

    // Touched from both coroutines and binder-thread GATT callbacks.
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    private var scanJob: Job? = null
    private val opLock = Mutex()

    override fun start() {
        if (_state.value == LinkState.STREAMING || _state.value == LinkState.CONNECTING) return
        _error.value = null
        _state.value = LinkState.SCANNING
        scanJob = scope.launch {
            val device = scanForBoard() ?: run {
                fail("No Force Board found. Is it on and not connected to another app?")
                return@launch
            }
            _state.value = LinkState.CONNECTING
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    override fun stop() {
        scanJob?.cancel()
        val g = gatt ?: run { _state.value = LinkState.IDLE; return }
        scope.launch {
            runCatching { writeChar(g, ForceBoard.WEIGHT_SERVICE, ForceBoard.DEVICE_MODE, ForceBoard.Mode.IDLE.payload()) }
            g.disconnect()
            g.close()
            gatt = null
            _state.value = LinkState.IDLE
        }
    }

    override fun hardwareTare(): Boolean {
        val g = gatt ?: return false
        scope.launch {
            runCatching { writeChar(g, ForceBoard.FORCEBOARD_SERVICE, ForceBoard.TARE, ForceBoard.TARE_PAYLOAD) }
                .onFailure { _error.value = "Tare failed: ${it.message}" }
        }
        return true
    }

    // ---------------------------------------------------------------------------------

    private suspend fun scanForBoard(): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        val filters = listOf(ScanFilter.Builder().setDeviceName(ForceBoard.ADVERTISED_NAME).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, cb)
        return try {
            withTimeout(SCAN_TIMEOUT_MS) { found.await() }
        } catch (e: Exception) {
            null
        } finally {
            scanner.stopScan(cb)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    pendingOp?.completeExceptionally(IllegalStateException("Disconnected"))
                    g.close()
                    if (gatt === g) gatt = null
                    if (_state.value != LinkState.IDLE) _state.value = LinkState.DISCONNECTED
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed ($status)")
                return
            }
            scope.launch {
                runCatching {
                    enableNotifications(g, ForceBoard.FORCEBOARD_SERVICE, ForceBoard.FORCE_DATA)
                    writeChar(g, ForceBoard.WEIGHT_SERVICE, ForceBoard.DEVICE_MODE, ForceBoard.Mode.STREAM.payload())
                    _state.value = LinkState.STREAMING
                }.onFailure { fail("Could not start stream: ${it.message}") }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != FORCE_DATA_UUID) return
            val now = System.currentTimeMillis()
            for (lbs in ForceBoard.parseForceData(value)) {
                _samples.tryEmit(RawSample(lbs.toDouble(), now))
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }
    }

    private suspend fun enableNotifications(g: BluetoothGatt, service: String, char: String) {
        val c = characteristic(g, service, char)
        check(g.setCharacteristicNotification(c, true)) { "setCharacteristicNotification failed" }
        val cccd = c.getDescriptor(UUID.fromString(ForceBoard.CCCD)) ?: error("No CCCD on $char")
        gattOp { g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
    }

    private suspend fun writeChar(g: BluetoothGatt, service: String, char: String, value: ByteArray) {
        val c = characteristic(g, service, char)
        gattOp { g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
    }

    /** Run one GATT op and suspend until its callback reports GATT_SUCCESS. */
    private suspend fun gattOp(issue: () -> Int) = opLock.withLock {
        val op = CompletableDeferred<Int>()
        pendingOp = op
        try {
            // Android can return ERROR_GATT_WRITE_REQUEST_BUSY briefly; retry a few times.
            var rc = issue()
            var tries = 0
            while (rc == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && tries++ < 5) {
                delay(20)
                rc = issue()
            }
            check(rc == BluetoothStatusCodes.SUCCESS) { "GATT op rejected ($rc)" }
            val status = withTimeout(OP_TIMEOUT_MS) { op.await() }
            check(status == BluetoothGatt.GATT_SUCCESS) { "GATT status $status" }
        } finally {
            pendingOp = null
        }
    }

    private fun characteristic(g: BluetoothGatt, service: String, char: String): BluetoothGattCharacteristic =
        g.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(char))
            ?: error("Missing characteristic $char")

    private fun fail(message: String) {
        _error.value = message
        _state.value = LinkState.ERROR
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 15_000L
        const val OP_TIMEOUT_MS = 3_000L
        val FORCE_DATA_UUID: UUID = UUID.fromString(ForceBoard.FORCE_DATA)
    }
}
