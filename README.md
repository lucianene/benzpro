# BenzPro

Personal Android OBD app (Kotlin, Jetpack Compose) for a garage:

- 2010 Mercedes-Benz E250 CDI Coupe (C207, OM651.911, 5G-Tronic 722.6 / EGS52)
- 2008 Kawasaki Z1000 ABS (KDS K-line)

Talks to a Vgate iCar Pro / ELM327 over Bluetooth SPP. Not a general-purpose scan tool.

## Build

JDK 17 and Android SDK 35. New Linux machine, USB phone, and “do not use an emulator”: [`.cursor/skills/compile/SKILL.md`](.cursor/skills/compile/SKILL.md).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:installDebug   # physical S23 via adb, not an AVD
```

`local.properties` (`sdk.dir=...`) is not committed. Debug-install on a new PC requires uninstalling the app first (different debug keystore).

## CI

[![Android CI](https://github.com/lucianene/benzpro/actions/workflows/android.yml/badge.svg)](https://github.com/lucianene/benzpro/actions/workflows/android.yml)

On every push and pull request, GitHub Actions runs unit tests and assembles the debug APK.
