import CoreBluetooth
import GripCore

/// Weiheng WH-C06: advertisement-only. Mirrors AndroidWhC06.kt.
/// Apple keeps the 2-byte company id at the front of manufacturer data, so we pass
/// `.withCompanyId` and GripCore shifts the offsets. (Android strips it.)
final class WhC06Scanner: NSObject, ForceSource {
    let name = "WH-C06"
    let streamUnit = WhC06.shared.STREAM_UNIT
    var onSample: ((Double, Int64) -> Void)?
    var onState: ((LinkState, String?) -> Void)?

    private var central: CBCentralManager!
    private var wantsRunning = false
    private var locked: UUID?
    private var lastSeen: Int64 = 0
    private var watchdog: Timer?

    func start() {
        wantsRunning = true
        locked = nil
        lastSeen = nowMs()
        if central == nil {
            central = CBCentralManager(delegate: self, queue: .main)
        } else if central.state == .poweredOn {
            scan()
        }
    }

    func stop() {
        wantsRunning = false
        watchdog?.invalidate()
        central?.stopScan()
        onState?(.idle, nil)
    }

    /// No BLE tare command; the caller falls back to ForceSession's software tare.
    func hardwareTare() -> Bool { false }

    private func scan() {
        onState?(.scanning, nil)
        // Duplicates are the whole point: every advertisement is a new reading.
        central.scanForPeripherals(withServices: nil,
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            guard let self else { return }
            if WhC06.shared.isStale(lastSeenMs: self.lastSeen, nowMs: nowMs()) {
                self.onState?(self.locked == nil ? .scanning : .disconnected, nil)
            }
        }
    }
}

extension WhC06Scanner: CBCentralManagerDelegate {
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
        guard let mfg = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              let reading = WhC06.shared.parse(payload: mfg.kotlin, source: .withCompanyId)
        else { return }
        if locked == nil { locked = p.identifier }
        guard p.identifier == locked else { return }
        lastSeen = nowMs()
        onState?(.streaming, nil)
        onSample?(reading.weight, lastSeen)
    }
}
