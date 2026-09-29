# Notices

## hangtime-grip-connect

The device protocols, UUIDs, command bytes, sample decoding, unit constants, and
tare/statistics/activity logic in `core/` are ported from
https://github.com/Stevie-Ray/hangtime-grip-connect, specifically:

- `packages/core/src/models/device/forceboard.model.ts` → `core/.../devices/ForceBoard.kt`
- `packages/core/src/models/device/wh-c06.model.ts` → `core/.../devices/WhC06.kt`
- `packages/core/src/models/device.model.ts` (applyTare, activityCheck, stats) → `core/.../ForceSession.kt`
- `packages/core/src/utils.ts` (unit conversion) → `core/.../ForceUnit.kt`
- `packages/core/src/interfaces/callback.interface.ts` (ForceMeasurement) → `core/.../ForceMeasurement.kt`

It is distributed under the following license:

```
BSD 2-Clause License

Copyright (c) 2024, Stevie-Ray Hartog

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

FOR THE AVIODANCE OF DOUBT, THIS SOFTWARE IS NOT OFFICIALY SUPPORTED, SUPPLIED OR MAINTAINED BY THE DEVICE MANUFACTURER.
BY USING THE SOFTWARE YOU ARE ACKNOWLEDGEING THIS AND UNDERSTAND THAT USING THIS SOFTWARE WILL INVALIDATE THE
MANUFACTURERS WARRANTY.
```

## Crane (sebws)

The signed interpretation of the WH-C06 weight field, the Apple manufacturer-data
offset, and the WH-C06 test vector in `core/src/commonTest/.../WhC06Test.kt` were
cross-checked against https://github.com/sebws/Crane (MIT License,
Copyright (c) 2024 sebws). No Crane source code is included.

## PitchSix

Force Board protocol details are also documented in PitchSix's
"Force Board Portable Public API 1.0": https://pitchsix.com/pages/downloads
