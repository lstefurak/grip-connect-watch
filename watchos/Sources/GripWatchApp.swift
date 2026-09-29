import SwiftUI
import GripCore

@main
struct GripWatchApp: App {
    @StateObject private var vm = WatchViewModel()

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                if vm.device == nil {
                    PickerView(vm: vm)
                } else {
                    LiveView(vm: vm)
                }
            }
        }
    }
}

struct PickerView: View {
    @ObservedObject var vm: WatchViewModel

    var body: some View {
        List(DeviceKind.allCases) { kind in
            Button(kind.rawValue) { vm.connect(kind) }
        }
        .navigationTitle("Connect")
    }
}

struct LiveView: View {
    @ObservedObject var vm: WatchViewModel

    var body: some View {
        VStack(spacing: 6) {
            Text(status).font(.caption2).foregroundStyle(vm.error == nil ? Color.secondary : Color.red)
                .multilineTextAlignment(.center)

            // Tap the number to cycle kg -> lbs -> N.
            VStack(spacing: 0) {
                Text(fmt(vm.measurement?.current))
                    .font(.system(size: 44, weight: .bold, design: .rounded))
                    .monospacedDigit()
                Text(vm.unit.symbol).font(.caption2)
            }
            .onTapGesture { vm.cycleUnit() }

            HStack(spacing: 16) {
                stat("Peak", vm.measurement?.peak)
                stat("Mean", vm.measurement?.mean)
            }

            HStack {
                Button("Tare") { vm.tare() }.tint(.accentColor)
                Button("Reset") { vm.resetStats() }
            }
            .font(.footnote)
        }
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Done") { vm.disconnect() }
            }
        }
    }

    private var status: String {
        if let e = vm.error { return e }
        if vm.measurement?.isTaring == true { return "Taring… hands off" }
        switch vm.link {
        case .scanning: return "Searching…"
        case .connecting: return "Connecting…"
        case .disconnected: return "Lost signal"
        case .streaming:
            if vm.measurement?.isActive == true { return "Pulling" }
            if let hz = vm.measurement?.samplingRateHz { return "Live · \(hz) Hz" }
            return "Live"
        default: return ""
        }
    }

    private func stat(_ label: String, _ value: Double?) -> some View {
        VStack(spacing: 0) {
            Text(fmt(value)).font(.headline).monospacedDigit()
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func fmt(_ v: Double?) -> String {
        guard let v else { return "--" }
        return String(format: "%.1f", v)
    }
}
