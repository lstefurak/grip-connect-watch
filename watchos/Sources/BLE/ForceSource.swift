import Foundation
import GripCore

enum LinkState { case idle, scanning, connecting, streaming, disconnected, error }

/// Swift twin of the Kotlin `ForceSource` interface in the Wear OS app.
/// Implementations only move bytes off the radio and through GripCore's parsers.
protocol ForceSource: AnyObject {
    var name: String { get }
    var streamUnit: ForceUnit { get }
    var onSample: ((Double, Int64) -> Void)? { get set }
    var onState: ((LinkState, String?) -> Void)? { get set }
    func start()
    func stop()
    /// True if the device tared itself; false means use a software tare in ForceSession.
    func hardwareTare() -> Bool
}

func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

extension Data {
    var kotlin: KotlinByteArray { AppleBytes.shared.toByteArray(data: self as NSData) }
}

extension KotlinByteArray {
    var data: Data { AppleBytes.shared.toNSData(bytes: self) as Data }
}
