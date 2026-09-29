import CoreBluetooth
import GripCore

/// PitchSix Force Board over GATT (CoreBluetooth). Mirrors AndroidForceBoard.kt.
/// CoreBluetooth queues writes for us, so no op-serialization is needed here.
final class ForceBoardClient: NSObject, ForceSource {
    let name = "Force Board"
    let streamUnit = ForceBoard.shared.STREAM_UNIT
    var onSample: ((Double, Int64) -> Void)?
    var onState: ((LinkState, String?) -> Void)?

    private let fb = ForceBoard.shared
    private lazy var forceService = CBUUID(string: fb.FORCEBOARD_SERVICE)
    private lazy var weightService = CBUUID(string: fb.WEIGHT_SERVICE)
    private lazy var forceData = CBUUID(string: fb.FORCE_DATA)
    private lazy var tareChar = CBUUID(string: fb.TARE)
    private lazy var modeChar = CBUUID(string: fb.DEVICE_MODE)

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var chars: [CBUUID: CBCharacteristic] = [:]
    private var wantsRunning = false
    private var streamRequested = false
    private var scanTimeout: DispatchWorkItem?

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
        scanTimeout?.cancel()
        central?.stopScan()
        if let p = peripheral, let mode = chars[modeChar] {
            p.writeValue(ForceBoard.Mode.idle.payload().data, for: mode, type: .withResponse)
        }
        if let p = peripheral { central?.cancelPeripheralConnection(p) }
        peripheral = nil
        chars = [:]
        onState?(.idle, nil)
    }

    func hardwareTare() -> Bool {
        guard let p = peripheral, let c = chars[tareChar] else { return false }
        p.writeValue(fb.TARE_PAYLOAD.data, for: c, type: .withResponse)
        return true
    }

    private func scan() {
        onState?(.scanning, nil)
        // The board doesn't reliably advertise its service UUID, so scan broadly and match by name.
        central.scanForPeripherals(withServices: nil)
        let timeout = DispatchWorkItem { [weak self] in
            guard let self, self.peripheral == nil else { return }
            self.central.stopScan()
            self.onState?(.error, "No Force Board found. Is it on and free?")
        }
        scanTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: timeout)
    }
}

extension ForceBoardClient: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn: if wantsRunning { scan() }
        case .unauthorized: onState?(.error, "Bluetooth permission denied")
        case .poweredOff: onState?(.error, "Bluetooth is off")
        default: break
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover p: CBPeripheral,
                        advertisementData: [String: Any], rssi: NSNumber) {
        let localName = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? p.name
        guard localName == fb.ADVERTISED_NAME, peripheral == nil else { return }
        scanTimeout?.cancel()
        central.stopScan()
        peripheral = p
        p.delegate = self
        onState?(.connecting, nil)
        central.connect(p)
    }

    func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
        streamRequested = false
        p.discoverServices([forceService, weightService])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        peripheral = nil
        onState?(.error, error?.localizedDescription ?? "Connection failed")
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        peripheral = nil
        chars = [:]
        if wantsRunning { onState?(.disconnected, nil) }
    }
}

extension ForceBoardClient: CBPeripheralDelegate {
    func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        p.services?.forEach { p.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        service.characteristics?.forEach { chars[$0.uuid] = $0 }
        // Start once both the notify and mode characteristics are known.
        guard !streamRequested, let data = chars[forceData], let mode = chars[modeChar] else { return }
        streamRequested = true
        p.setNotifyValue(true, for: data)
        p.writeValue(ForceBoard.Mode.stream.payload().data, for: mode, type: .withResponse)
    }

    func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        if let error { onState?(.error, "Notify failed: \(error.localizedDescription)"); return }
        if c.uuid == forceData && c.isNotifying { onState?(.streaming, nil) }
    }

    func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == forceData, let value = c.value else { return }
        let t = nowMs()
        let samples = fb.parseForceData(packet: value.kotlin)
        for i in 0..<samples.size {
            onSample?(Double(samples.get(index: i)), t)
        }
    }
}
