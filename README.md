# Grip Connect Watch

Live grip-force readout on your wrist for Bluetooth climbing dynamometers, on **Wear OS** (Pixel Watch) and **Apple Watch**, from one shared Kotlin core.

A Kotlin Multiplatform port of the device protocols in Stevie-Ray Hartog's [hangtime-grip-connect](https://github.com/Stevie-Ray/hangtime-grip-connect). See [NOTICE.md](NOTICE.md).

| Device | Wear OS | watchOS | How it talks |
|---|---|---|---|
| [PitchSix Force Board](https://pitchsix.com/products/force-board-portable) | ✅ | ✅ | GATT: notify on force data, write a mode byte to start/stop |
| [Weiheng WH-C06](https://weihengmanufacturer.com/products/wh-c06-bluetooth-300kg-hanging-scale/) | ✅ | ✅ | Advertisement-only: weight rides in manufacturer data, no connection |

## Layout

```
core/      Kotlin Multiplatform. Protocols, parsing, session math. No Bluetooth dependency.
             jvm     -> used directly by the Wear OS app, and where tests run
             watchos -> GripCore.xcframework for the Apple Watch app
wear/      Wear OS app: Compose for Wear OS + android.bluetooth
watchos/   watchOS app: SwiftUI + CoreBluetooth (XcodeGen project)
```

**One base, two apps.** Each watch has a thin, native Bluetooth layer that only gets bytes off the radio. The bytes then go through the same Kotlin parsers into the same `ForceSession`, which handles tare, peak/mean/min, unit conversion, sample rate and activity detection.

The watch-side code is small by design. `AndroidForceBoard.kt` ↔ `ForceBoardClient.swift`, `AndroidWhC06.kt` ↔ `WhC06Scanner.swift` and `LiveViewModel.kt` ↔ `WatchViewModel.swift` are line-for-line twins.

A shared BLE library wasn't used because [Kable](https://github.com/JuulLabs/kable), the usual KMP option, has no watchOS target. Keeping the core BLE-free also makes it trivially testable.

## Build

**Core tests** (any machine with JDK 17+):
```bash
./gradlew :core:jvmTest
```

**Wear OS:** open the repo in Android Studio, run the `wear` configuration on a Pixel Watch or a Wear OS emulator. BLE needs real hardware. Requires Wear OS 4+ (API 33).

**watchOS** (macOS + Xcode 15+):
```bash
./gradlew :core:assembleGripCoreReleaseXCFramework   # once, before first open
cd watchos && brew install xcodegen && xcodegen
open GripWatch.xcodeproj
```
The Xcode project rebuilds the framework in a pre-build phase after that.

## Using it

Pick a device, then tap **Tare** with nothing on the sensor.
- **Force Board** does a hardware tare.
- **WH-C06** does a 2-second software tare in the shared core; the scale has no Bluetooth tare command.

Tap the big number to cycle kg → lbs → N. **Reset** clears peak and mean between reps.

## Protocol notes

- **Force Board samples** are integer lbs, `b0 * 32768 + b1 * 256 + b2`, 1–6 per notification. That multiplier is straight from PitchSix's [public API](https://pitchsix.com/pages/downloads). Modes written to the Device Mode characteristic are `0x04` stream, `0x05` tare, `0x06` quick start and `0x07` idle.
- **WH-C06 weight** is a big-endian signed int16 in hundredths, carried under company id `0x0100`. It sits at offset 10 on Android, which strips the company id, and at offset 12 on Apple, which keeps it. `WhC06.PayloadSource` handles the difference.
- **Differences from upstream:**
  - WH-C06 weight is read as signed (as [Crane](https://github.com/sebws/Crane) does), not unsigned.
  - Tare and activity detection run on sample timestamps rather than timers, so they behave identically on both platforms.
  - The activity threshold is interpreted in the display unit.

## Status / next steps

- [ ] Verify both devices on real hardware. The WH-C06 status nibbles (stable / unit) are unconfirmed.
- [ ] Wear OS: foreground service + Ongoing Activity so streaming survives the app leaving the foreground.
- [ ] watchOS: run inside an `HKWorkoutSession` to keep the app alive with wrist down.
- [ ] Session history + CSV export (upstream has `download()`).
- [ ] Port more devices from upstream (Progressor is the next easiest).

## License

BSD 2-Clause, matching upstream. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

Not affiliated with, endorsed by, or supported by PitchSix, Weiheng, or the hangtime-grip-connect project. Use at your own risk.
