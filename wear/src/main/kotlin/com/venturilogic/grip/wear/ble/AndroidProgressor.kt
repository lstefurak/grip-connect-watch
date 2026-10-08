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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.venturilogic.grip.ForceUnit
import com.venturilogic.grip.devices.Progressor
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tindeq Progressor over GATT. Every command is one opcode byte written to the control
 * point; weight batches and the low-power warning arrive as data point notifications.
 *
 * Android GATT allows one outstanding operation at a time, so every write goes through
 * [gattOp], which holds a mutex until the matching callback fires.
 *
 * Real units stop measuring after a tare, so [hardwareTare] restarts the measurement, and
 * a no-data watchdog restarts it once more if the stream goes quiet. Delayed actions check
 * the link [generation], so nothing is written after [stop] or a disconnect.
 *
 * Permissions (BLUETOOTH_SCAN / BLUETOOTH_CONNECT) are requested by MainActivity before
 * [start] is ever called, hence the @SuppressLint.
 */
@SuppressLint("MissingPermission")
class AndroidProgressor(
    context: Context,
    private val scope: CoroutineScope,
) : ForceSource {
    override val name = "Progressor"
    override val streamUnit: ForceUnit = Progressor.STREAM_UNIT

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
    @Volatile private var mtuRequested = false
    @Volatile private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    @Volatile private var streaming = false
    @Volatile private var tareBlackout = false
    @Volatile private var lowPowerSeen = false
    @Volatile private var lastDataMs = 0L
    @Volatile private var armedMs = 0L
    @Volatile private var scanJob: Job? = null
    @Volatile private var watchdog: Job? = null
    private val generation = AtomicInteger()
    private val opLock = Mutex()

    override fun start() {
        if (_state.value == LinkState.STREAMING || _state.value == LinkState.CONNECTING) return
        _error.value = null
        _state.value = LinkState.SCANNING
        lowPowerSeen = false
        val gen = generation.get()
        scanJob = scope.launch {
            val device = scanForProgressor()
            if (!isCurrent(gen)) return@launch
            if (device == null) {
                fail("No Progressor found. Is it on and not connected to another app?")
                return@launch
            }
            _state.value = LinkState.CONNECTING
            gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            // Connect, discovery, MTU, notifications and the first START all have to fit in here.
            delay(SETUP_TIMEOUT_MS)
            if (isCurrent(gen) && _state.value == LinkState.CONNECTING) fail("Connection timed out")
        }
    }

    override fun stop() = release(LinkState.IDLE, message = null)

    override fun hardwareTare(): Boolean {
        val g = gatt ?: return false
        if (_state.value != LinkState.STREAMING) return false
        if (tareBlackout) return true
        // Drop samples until the measurement is restarted below.
        tareBlackout = true
        val gen = generation.get()
        scope.launch {
            runCatching { writeCommand(g, Progressor.Command.TARE_SCALE) }
                .onFailure {
                    if (!isCurrent(gen)) return@launch
                    tareBlackout = false
                    _error.value = "Tare failed: ${it.message}"
                }
            delay(TARE_RESTART_DELAY_MS)
            if (!isCurrent(gen)) return@launch
            runCatching { writeCommand(g, Progressor.Command.START_WEIGHT_MEAS) }
            if (!isCurrent(gen)) return@launch
            tareBlackout = false
            armedMs = System.currentTimeMillis()
        }
        return true
    }

    // ---------------------------------------------------------------------------------

    private suspend fun scanForProgressor(): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<BluetoothDevice>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Check every result: the name may only show up later, with the scan response.
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name
                val named = name?.startsWith(Progressor.NAME_PREFIX, ignoreCase = true) == true
                if (named || record?.serviceUuids?.contains(SERVICE_PARCEL_UUID) == true) {
                    found.complete(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        // Unfiltered: ScanFilter can't prefix-match a name, and the service UUID most likely
        // rides only in the scan response. Match in onScanResult instead.
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(emptyList(), settings, cb)
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
                    if (gatt !== g) return // stop() or a failure already let go of this link.
                    gatt = null
                    when {
                        _state.value == LinkState.CONNECTING -> fail("Connection failed ($status)")
                        // Tindeq: the unit switches itself off after the low-power warning.
                        lowPowerSeen -> release(LinkState.DISCONNECTED, "Progressor switched off: battery empty.")
                        else -> release(LinkState.DISCONNECTED, message = null)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt !== g) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed ($status)")
                return
            }
            val data = characteristic(g, DATA_POINT_UUID)
            val control = characteristic(g, CONTROL_POINT_UUID)
            if (data == null || control == null) {
                fail("Not a supported Progressor (missing characteristic)")
                return
            }
            val type = writeTypeOf(control)
            if (!data.has(BluetoothGattCharacteristic.PROPERTY_NOTIFY) || type == null) {
                fail("Not a supported Progressor (unexpected characteristic properties)")
                return
            }
            writeType = type
            val gen = generation.get()
            scope.launch {
                runCatching {
                    // Bigger MTU, bigger batches. At the default 23 the stream still works.
                    runCatching { requestMtu(g) }
                    enableNotifications(g, data)
                    if (!isCurrent(gen)) return@launch
                    streaming = true
                    writeCommand(g, Progressor.Command.START_WEIGHT_MEAS)
                    if (!isCurrent(gen)) return@launch
                    _state.value = LinkState.STREAMING
                    armWatchdog(g, gen)
                }.onFailure { if (isCurrent(gen)) fail("Could not start stream: ${it.message}") }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid != DATA_POINT_UUID) return
            val now = System.currentTimeMillis()
            if (Progressor.isLowPowerWarning(value)) {
                // Non-fatal, once per connection: keep streaming until the unit switches off.
                if (!lowPowerSeen) {
                    lowPowerSeen = true
                    _error.value = "Progressor battery low"
                }
                return
            }
            lastDataMs = now // Any frame keeps the watchdog quiet, even one dropped below.
            if (!streaming || tareBlackout) return
            for (kg in Progressor.parseWeights(value)) {
                _samples.tryEmit(RawSample(kg, now))
            }
        }

        // Also fires for WRITE_TYPE_NO_RESPONSE (local flow control), so gattOp completes either way.
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        // Can also fire unprompted on a connection event; only complete our own request.
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (mtuRequested) pendingOp?.complete(status)
        }
    }

    /**
     * No-data watchdog. After [NO_DATA_TIMEOUT_MS] without a frame, restart the measurement
     * once (STOP, pause, START); if it is still quiet [NO_DATA_TIMEOUT_MS] later, give up.
     * Covers a first frame that never comes, a tare that didn't restart, and firmware stalls.
     */
    private fun armWatchdog(g: BluetoothGatt, gen: Int) {
        armedMs = System.currentTimeMillis()
        watchdog = scope.launch {
            var restartedAt: Long? = null
            while (isCurrent(gen)) {
                delay(WATCHDOG_TICK_MS)
                if (!streaming || tareBlackout) continue
                val now = System.currentTimeMillis()
                if (now - maxOf(lastDataMs, armedMs) < NO_DATA_TIMEOUT_MS) continue
                val last = restartedAt
                if (last != null && lastDataMs < last) {
                    fail("Progressor stopped sending data")
                    return@launch
                }
                restartedAt = now
                runCatching { writeCommand(g, Progressor.Command.STOP_WEIGHT_MEAS) }
                delay(WATCHDOG_RESTART_GAP_MS)
                if (!isCurrent(gen)) return@launch
                runCatching { writeCommand(g, Progressor.Command.START_WEIGHT_MEAS) }
                armedMs = System.currentTimeMillis()
            }
        }
    }

    private suspend fun requestMtu(g: BluetoothGatt) {
        try {
            gattOp {
                mtuRequested = true
                if (g.requestMtu(REQUESTED_MTU)) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
            }
        } finally {
            mtuRequested = false
        }
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        check(g.setCharacteristicNotification(c, true)) { "setCharacteristicNotification failed" }
        val cccd = c.getDescriptor(CCCD_UUID) ?: error("No CCCD on the data point")
        gattOp { g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
    }

    private suspend fun writeCommand(g: BluetoothGatt, command: Progressor.Command) {
        val c = characteristic(g, CONTROL_POINT_UUID) ?: error("Missing control point")
        gattOp { g.writeCharacteristic(c, command.payload(), writeType) }
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

    private fun characteristic(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.getService(SERVICE_UUID)?.getCharacteristic(uuid)

    /** With response when the unit offers it (flow control), else without; null if not writable. */
    private fun writeTypeOf(c: BluetoothGattCharacteristic): Int? = when {
        c.has(BluetoothGattCharacteristic.PROPERTY_WRITE) -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.has(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else -> null
    }

    private fun BluetoothGattCharacteristic.has(property: Int) = (properties and property) != 0

    /**
     * The stop sequence, shared by [stop], failures and disconnects: end the link, report
     * [endState], then write STOP (best effort, capped at [STOP_WRITE_TIMEOUT_MS]) and drop
     * GATT. No sleep command: the next set would need a button press to wake the unit.
     */
    private fun release(endState: LinkState, message: String?) {
        scanJob?.cancel()
        endLink()
        if (message != null) _error.value = message
        _state.value = endState
        val g = gatt ?: return
        gatt = null
        // viewModelScope is already cancelled by the time onCleared() stops us.
        if (!scope.isActive) {
            g.disconnect()
            g.close()
            return
        }
        scope.launch {
            runCatching { withTimeout(STOP_WRITE_TIMEOUT_MS) { writeCommand(g, Progressor.Command.STOP_WEIGHT_MEAS) } }
            g.disconnect()
            g.close()
        }
    }

    private fun fail(message: String) = release(LinkState.ERROR, message)

    /** Invalidate the link's delayed actions (setup timeout, tare restart, watchdog) and stop forwarding. */
    private fun endLink() {
        generation.incrementAndGet()
        streaming = false
        tareBlackout = false
        watchdog?.cancel()
    }

    private fun isCurrent(gen: Int) = generation.get() == gen

    private companion object {
        const val SCAN_TIMEOUT_MS = 15_000L
        const val SETUP_TIMEOUT_MS = 10_000L
        const val OP_TIMEOUT_MS = 3_000L
        const val STOP_WRITE_TIMEOUT_MS = 1_000L
        const val TARE_RESTART_DELAY_MS = 500L
        const val NO_DATA_TIMEOUT_MS = 3_000L
        const val WATCHDOG_TICK_MS = 500L
        const val WATCHDOG_RESTART_GAP_MS = 500L
        const val REQUESTED_MTU = 517
        val SERVICE_UUID: UUID = UUID.fromString(Progressor.SERVICE)
        val SERVICE_PARCEL_UUID = ParcelUuid(SERVICE_UUID)
        val DATA_POINT_UUID: UUID = UUID.fromString(Progressor.DATA_POINT)
        val CONTROL_POINT_UUID: UUID = UUID.fromString(Progressor.CONTROL_POINT)
        val CCCD_UUID: UUID = UUID.fromString(Progressor.CCCD)
    }
}
