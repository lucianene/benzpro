---
name: benzpro
description: >-
  Extends the BenzPro Android OBD app (Kotlin Compose, ELM327 iCarPro).
  Use when working in the benzpro repo, adding vehicles, PIDs, Mercedes
  modules, Kawasaki KWP, Bluetooth connect, or ELM AT commands.
---

# BenzPro

Single-module Compose app (`app.benzpro`) for a garage. PlayNotes Gradle/Compose style: one Activity, one `AndroidViewModel`, pane enum (no NavHost), JSON file stores, snackbars.

## This garage

Default vehicle is the E250. Do not treat this as a generic OBD toolkit.

| Id | VIN | What it is |
|---|---|---|
| `e250_c207` | `WDD2073031F010216` | C207 E250 CDI, OM651.911, Euro 5 DPF, **5G-Tronic 722.6 / EGS52** (not 722.9/VGS) |
| `z1000_abs` | `JKAZRT00BCA020081` | 2008 Z1000 ABS, ZRT00B, Euro 3. Under-seat **4-pin KDS** K-line. **ABS is a separate plug.** |

Registry: [`vehicle/VehicleRegistry.kt`](app/src/main/java/app/benzpro/vehicle/VehicleRegistry.kt)

## Layers (keep Bluetooth/AT out of UI)

```
BenzProScreen + BenzProViewModel
  → ObdSession
      → ElmClient (mutex, wait for `>`)
          → ElmTransport / SppTransport (RFCOMM UUID 00001101-…)
      → DiagnosticBackend (MercedesBackend | KawasakiBackend)
```

- Transport: [`transport/ElmTransport.kt`](app/src/main/java/app/benzpro/transport/ElmTransport.kt), [`SppTransport.kt`](app/src/main/java/app/benzpro/transport/SppTransport.kt)
- ELM: [`elm/ElmClient.kt`](app/src/main/java/app/benzpro/elm/ElmClient.kt)
- Session: [`session/ObdSession.kt`](app/src/main/java/app/benzpro/session/ObdSession.kt)
- Backends: [`mercedes/MercedesBackend.kt`](app/src/main/java/app/benzpro/mercedes/MercedesBackend.kt), [`kawasaki/KawasakiBackend.kt`](app/src/main/java/app/benzpro/kawasaki/KawasakiBackend.kt)

ELM up ≠ ECU up. Ignition-off must not drop the socket.

All ELM I/O is serialized in `ElmClient`. Do not fire parallel AT/OBD commands.

## Connection UX

- Saved adapter: `filesDir/adapter.json` (`AdapterStore`)
- Auto-connect on launch if a MAC is saved
- Connect button: green Connect / spinner Connecting / red Disconnect
- Long-press Connect: device sheet
- Chips: ELM + ECU
- Keep screen on: `FLAG_KEEP_SCREEN_ON` in [`MainActivity.kt`](app/src/main/java/app/benzpro/MainActivity.kt)
- Snackbars via `snackbarMessage` on the ViewModel

## Add a vehicle

1. Add a `VehicleProfile` in `VehicleRegistry` (id, VIN, `ElmInitKind`, capability flags).
2. Implement `DiagnosticBackend` (probe, read/clear, live PIDs).
3. Wire it in `ObdSession.backend`.
4. Do **not** fork screens. Hide Modules / health strip with `VehicleCapabilities`.

Future BLE (vLinker MS): new `ElmTransport` + `transportKind` on `SavedAdapter`. Same `ElmClient`.

## Add a PID

Benz: [`obd/PidCatalog.kt`](app/src/main/java/app/benzpro/obd/PidCatalog.kt)  
Z1000: [`kawasaki/KawasakiBackend.kt`](app/src/main/java/app/benzpro/kawasaki/KawasakiBackend.kt) `KawasakiPids`

Selection persisted per vehicle in `pids.json`.

## Mercedes modules

Catalog: [`mercedes/MercedesModules.kt`](app/src/main/java/app/benzpro/mercedes/MercedesModules.kt)  
Addressing: 11-bit `7Ex` then 29-bit `ATCP 18` + `ATSH DAxxF1`. Persist hits in `modules.json`.

C207 notes: [`mercedes/MercedesNotes.kt`](app/src/main/java/app/benzpro/mercedes/MercedesNotes.kt) — 722.6 text, never 7G conductor-plate copy.

## Do not send to the car

Forced DPF regen, injector coding, SCN, security access `0x27`, ASSYST reset, airbag crash clear, DPF/EGR delete, Kawasaki ABS dealer routines.

Show those as disabled (“needs dealer-level adapter”) if you add UI later.

## Debug device

Physical Samsung S23 Ultra only. Linux SDK, adb, and install: [compile](../compile/SKILL.md). Never create or use an emulator / AVD.

## Stores

JSON in `filesDir`: `adapter.json`, `vehicles.json`, `pids.json`, `modules.json`, `dtc_history.json` (history keyed by vehicle id).
