import Foundation
import GripCore

enum DeviceKind: String, CaseIterable, Identifiable {
    case forceBoard = "Force Board"
    case whc06 = "WH-C06"
    var id: String { rawValue }
}

/// Swift twin of LiveViewModel.kt: platform ForceSource -> shared ForceSession -> UI.
@MainActor
final class WatchViewModel: ObservableObject {
    @Published private(set) var device: DeviceKind?
    @Published private(set) var measurement: ForceMeasurement?
    @Published private(set) var link: LinkState = .idle
    @Published private(set) var error: String?
    @Published private(set) var unit: ForceUnit = .kg

    private var source: ForceSource?
    private var session: ForceSession?

    /// Shorter than upstream's 5 s default: you're standing at the board waiting.
    private let softwareTareMs: Int64 = 2_000

    func connect(_ kind: DeviceKind) {
        disconnect()
        let src: ForceSource = kind == .forceBoard ? ForceBoardClient() : WhC06Scanner()
        let s = ForceSession(streamUnit: src.streamUnit,
                             displayUnit: unit,
                             activity: ActivityConfig(threshold: 2.5, durationMs: 1_000))
        src.onSample = { [weak self] raw, t in
            Task { @MainActor in self?.measurement = s.ingest(raw: raw, timestampMs: t) }
        }
        src.onState = { [weak self] state, err in
            Task { @MainActor in
                self?.link = state
                self?.error = err
            }
        }
        source = src
        session = s
        measurement = nil
        device = kind
        src.start()
    }

    func disconnect() {
        source?.stop()
        source = nil
        session = nil
        device = nil
        link = .idle
        error = nil
    }

    func tare() {
        guard let s = session else { return }
        if source?.hardwareTare() == true {
            s.clearTare()
        } else {
            _ = s.tare(nowMs: nowMs(), durationMs: softwareTareMs)
        }
        s.resetStats()
    }

    func resetStats() { session?.resetStats() }

    func cycleUnit() {
        unit = unit == .kg ? .lbs : (unit == .lbs ? .n : .kg)
        session?.displayUnit = unit
    }
}
