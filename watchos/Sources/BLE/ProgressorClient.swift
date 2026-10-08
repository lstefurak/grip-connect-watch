import CoreBluetooth
import GripCore

/// Tindeq Progressor over GATT (CoreBluetooth). Mirrors AndroidProgressor.kt.
/// Every command is one opcode byte written to the control point; weight batches and the
/// low-power warning arrive as data point notifications.
///
/// CoreBluetooth queues writes for us; `send` only adds completions, and waits for room
/// when the control point is write-without-response.
///
/// Real units stop measuring after a tare, so `hardwareTare` restarts the measurement, and
/// a no-data watchdog restarts it once more if the stream goes quiet. Delayed actions check
/// the link `generation`, so nothing is written after `stop` or a disconnect.
final class ProgressorClient: NSObject, ForceSource {
    let name = "Progressor"
    let streamUnit = Progressor.shared.STREAM_UNIT
    var onSample: ((Double, Int64) -> Void)?
    var onState: ((LinkState, String?) -> Void)?

    private let pg = Progressor.shared
    private lazy var progressorService = CBUUID(string: pg.SERVICE)
    private lazy var dataPoint = CBUUID(string: pg.DATA_POINT)
    private lazy var controlPoint = CBUUID(string: pg.CONTROL_POINT)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var control: CBCharacteristic?
    private var writeType: CBCharacteristicWriteType = .withResponse
    private var outbox: [(command: Progressor.Command, done: (Error?) -> Void)] = []
    private var writeDone: [(Error?) -> Void] = []
    private var wantsRunning = false
    private var streaming = false
    private var tareBlackout = false
    private var lowPowerSeen = false
    private var generation = 0
    private var lastDataMs: Int64 = 0
    private var armedMs: Int64 = 0
    private var restartedMs: Int64?
    private var scanTimeout: DispatchWorkItem?
    private var setupTimeout: DispatchWorkItem?
    private var watchdog: Timer?

    func start() {
        wantsRunning = true
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else if central.state == .poweredOn {
            scan()
        }
    }

    func stop() {
        wantsRunning = false
        teardown()
        onState?(.idle, nil)
    }

    func hardwareTare() -> Bool {
        // The watchdog is armed exactly while streaming; before that, use a software tare.
        guard watchdog != nil else { return false }
        if tareBlackout { return true }
        // Drop samples until the measurement is restarted below.
        tareBlackout = true
        let gen = generation
        send(.tareScale) { [weak self] error in
            guard let self, let error, gen == self.generation else { return }
            self.tareBlackout = false
            self.onState?(.streaming, "Tare failed: \(error.localizedDescription)")
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, gen == self.generation else { return }
            self.send(.startWeightMeas) { [weak self] _ in
                guard let self, gen == self.generation else { return }
                self.tareBlackout = false
                self.armedMs = nowMs()
            }
        }
        return true
    }

    private func scan() {
        onState?(.scanning, nil)
        // Unfiltered: the name is matched by prefix, and the service UUID most likely rides
        // only in the scan response. Match in didDiscover instead.
        central.scanForPeripherals(withServices: nil)
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, self.peripheral == nil else { return }
            self.central.stopScan()
            self.onState?(.error, "No Progressor found. Is it on and free?")
        }
        scanTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: timeout)
    }

    /// No-data watchdog. After 3 s without a frame, restart the measurement once (STOP,
    /// pause, START); if it is still quiet 3 s later, give up. Covers a first frame that
    /// never comes, a tare that didn't restart, and firmware stalls.
    private func armWatchdog() {
        armedMs = nowMs()
        restartedMs = nil
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.checkStream()
        }
    }

    private func checkStream() {
        let now = nowMs()
        guard streaming, !tareBlackout, now - max(lastDataMs, armedMs) >= 3_000 else { return }
        if let last = restartedMs, lastDataMs < last {
            fail("Progressor stopped sending data")
            return
        }
        restartedMs = now
        armedMs = now // Keeps the ticks during the pause below quiet.
        let gen = generation
        send(.stopWeightMeas)
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in
            guard let self, gen == self.generation else { return }
            self.send(.startWeightMeas)
            self.armedMs = nowMs()
        }
    }

    /// Write one command to the control point. `done` runs once it has landed: on the write
    /// callback with response, or as soon as it is issued without.
    private func send(_ command: Progressor.Command, then done: @escaping (Error?) -> Void = { _ in }) {
        guard peripheral != nil, control != nil else { done(CBError(.notConnected)); return }
        outbox.append((command: command, done: done))
        flush()
    }

    /// Issue queued commands in order. Without response, only while CoreBluetooth has room;
    /// peripheralIsReady(toSendWriteWithoutResponse:) calls back here when it does again.
    private func flush() {
        guard let p = peripheral, let c = control else { return }
        while let next = outbox.first {
            if writeType == .withoutResponse && !p.canSendWriteWithoutResponse { return }
            outbox.removeFirst()
            p.writeValue(next.command.payload().data, for: c, type: writeType)
            if writeType == .withResponse { writeDone.append(next.done) } else { next.done(nil) }
        }
    }

    /// The stop sequence, shared by stop(), failures and disconnects: invalidate the link's
    /// delayed actions (setup timeout, tare restart, watchdog), then write STOP (best effort,
    /// at most 1 s) and drop the link. No sleep command: the next set would need a button
    /// press to wake the unit.
    private func teardown() {
        generation += 1
        streaming = false
        tareBlackout = false
        scanTimeout?.cancel()
        setupTimeout?.cancel()
        watchdog?.invalidate()
        watchdog = nil
        outbox = []
        central?.stopScan()
        if let p = peripheral {
            // Strong self on purpose: the view model lets go of us straight away, but the
            // link still has to be dropped once STOP lands.
            var dropped = false
            let drop: () -> Void = {
                guard !dropped else { return }
                dropped = true
                self.writeDone = []
                self.central.cancelPeripheralConnection(p)
            }
            send(.stopWeightMeas) { _ in drop() }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) { drop() }
        }
        peripheral = nil
        control = nil
    }

    private func fail(_ message: String) {
        wantsRunning = false
        teardown()
        onState?(.error, message)
    }
}

extension ProgressorClient: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: if wantsRunning { scan() }
        case .unauthorized: onState?(.error, "Bluetooth permission denied")
        case .poweredOff:
            // The link is gone without a disconnect callback; stop the watchdog and timers.
            peripheral = nil
            teardown()
            onState?(.error, "Bluetooth is off")
        default: break
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi: NSNumber) {
        let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? p.name
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        // "Progressor_4760" on a real unit. A unit stuck in its DFU bootloader advertises
        // "dfu_target_xxxx" and does not match.
        guard pg.matchesAdvertisement(localName: localName, serviceUuids: services.map { $0.uuidString }),
              peripheral == nil else { return }
        scanTimeout?.cancel()
        central.stopScan()
        peripheral = p
        p.delegate = self
        onState?(.connecting, nil)
        // Connect, discovery, notifications and the first START all have to fit in here.
        let setup = DispatchWorkItem { [weak self] in self?.fail("Connection timed out") }
        setupTimeout = setup
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: setup)
        central.connect(p)
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        lowPowerSeen = false
        p.discoverServices([progressorService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        peripheral = nil
        fail(error?.localizedDescription ?? "Connection failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        guard p == peripheral else { return } // stop() or a failure already let go of this link.
        let wasStreaming = watchdog != nil
        peripheral = nil
        writeDone = [] // Those writes will never land.
        guard wasStreaming else {
            fail(error?.localizedDescription ?? "Connection failed")
            return
        }
        teardown()
        // Tindeq: the unit switches itself off after the low-power warning.
        onState?(.disconnected, lowPowerSeen ? "Progressor switched off: battery empty." : nil)
    }
}

extension ProgressorClient: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard p == peripheral else { return }
        if let error { fail("Service discovery failed: \(error.localizedDescription)"); return }
        // Only the Progressor service: the unit's Nordic DFU service (0xFE59) is never touched.
        guard let service = p.services?.first(where: { $0.uuid == progressorService }) else {
            fail("Not a supported Progressor (missing characteristic)")
            return
        }
        p.discoverCharacteristics([dataPoint, controlPoint], for: service)
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard p == peripheral else { return }
        let found = service.characteristics ?? []
        guard let data = found.first(where: { $0.uuid == dataPoint }),
              let ctrl = found.first(where: { $0.uuid == controlPoint }) else {
            fail("Not a supported Progressor (missing characteristic)")
            return
        }
        let props = ctrl.properties
        guard data.properties.contains(.notify),
              props.contains(.write) || props.contains(.writeWithoutResponse) else {
            fail("Not a supported Progressor (unexpected characteristic properties)")
            return
        }
        // With response when the unit offers it (flow control), else without.
        writeType = props.contains(.write) ? .withResponse : .withoutResponse
        control = ctrl
        p.setNotifyValue(true, for: data)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard p == peripheral else { return }
        if let error { fail("Notify failed: \(error.localizedDescription)"); return }
        guard c.uuid == dataPoint, c.isNotifying, !streaming else { return }
        // Start only once notifications are on, so the first batch can't be missed.
        streaming = true
        let gen = generation
        send(.startWeightMeas) { [weak self] error in
            guard let self, gen == self.generation else { return }
            if let error { self.fail("Could not start stream: \(error.localizedDescription)"); return }
            self.setupTimeout?.cancel()
            self.onState?(.streaming, nil)
            self.armWatchdog()
        }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == dataPoint, p == peripheral, let value = c.value else { return }
        let t = nowMs()
        let packet = value.kotlin
        if pg.isLowPowerWarning(packet: packet) {
            // Non-fatal, once per connection: keep streaming until the unit switches off.
            if !lowPowerSeen { onState?(.streaming, "Progressor battery low") }
            lowPowerSeen = true
            return
        }
        // Only a valid weight frame counts as data: truncated or unknown frames must not
        // hide a stalled stream from the watchdog.
        if pg.responseOf(packet: packet) == Progressor.Response.weightMeasurement { lastDataMs = t }
        guard streaming, !tareBlackout else { return }
        let weights = pg.parseWeights(packet: packet)
        for i in 0..<weights.size {
            onSample?(weights.get(index: i), t)
        }
    }

    func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        // Write callbacks come back in write order, so the oldest completion is this write's.
        guard c.uuid == controlPoint, !writeDone.isEmpty else { return }
        let done = writeDone.removeFirst()
        done(error)
    }

    func peripheralIsReady(toSendWriteWithoutResponse p: CBPeripheral) {
        flush()
    }
}
