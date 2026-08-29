---
name: compile
description: >-
  Sets up Linux JDK/SDK, compiles BenzPro, and installs the debug APK on the
  physical Samsung S23 via adb. Use when compiling, installing, sending to the
  phone, setting up a new computer, Gradle, ANDROID_HOME, or adb. Never use an
  emulator or AVD.
---

# Compile and install BenzPro

Physical phone only. **Never** create, start, or install an Android emulator, AVD, `system-images`, `emulator` package, or qemu/KVM Android image.

## Target

| | |
|---|---|
| App | `app.benzpro` |
| Activity | `app.benzpro/.MainActivity` |
| Phone | Samsung S23 Ultra **SM-S918B** |
| Serial | `R5CX12R1D9J` |
| USB | adb (USB debugging). Not wireless unless the user says so. |
| This garage SDK | `/home/lucifer/Android/Sdk` |
| New machine SDK | `$HOME/Android/Sdk` |

Stack: JDK **17**, AGP 8.7.3, Gradle **8.11.1** (wrapper), `compileSdk` **35**. Match CI in `.github/workflows/android.yml`.

## New Linux machine

Debian/Ubuntu:

```bash
sudo apt update
sudo apt install -y openjdk-17-jdk git unzip wget curl usbutils android-sdk-platform-tools-common
java -version   # must be 17
```

- `android-sdk-platform-tools-common` is **udev rules** (plug a phone without running as root). Do **not** rely on distro `adb`/`gradle` for the build.
- Do **not** `apt install` emulator, qemu-kvm, or `google-android-emulator`.

Put the Android **command-line tools** (not Android Studio) in `$HOME/Android/Sdk/cmdline-tools/latest/`, then:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0"
yes | sdkmanager --licenses
```

If `JAVA_HOME` is missing, `update-java-alternatives -l` and point at the 17 path.

In the repo root (never commit this file):

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

On this garage PC, `local.properties` is already `sdk.dir=/home/lucifer/Android/Sdk`.

Network: first `./gradlew` downloads the wrapper distribution and Maven artifacts.

## Phone

1. Phone: Developer options → **USB debugging** on. Unlock the screen.
2. Cable: data USB (not charge-only). USB mode **File transfer / MTP**.
3. First plug: accept the RSA fingerprint on the phone.
4. User on the plug (`lsusb` shows Samsung `04e8`). Then:

```bash
adb devices -l
# R5CX12R1D9J    device  ... model:SM_S918B
```

Always pass `-s R5CX12R1D9J`. Unauthorized → tap Allow on the phone. Offline → unplug, `adb kill-server`, replug. Empty list → udev, cable, or debugging off.

**Never** `adb emulator`, `emulator`, `avdmanager`, or `sdkmanager "system-images;..."`.

## After a code change

From the repo root. Cursor: run Gradle/adb with full permissions (`all`) — signing writes `~/.android` and adb needs USB.

```bash
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

./gradlew :app:testDebugUnitTest :app:installDebug
adb -s R5CX12R1D9J shell am start -n app.benzpro/.MainActivity
```

Use the committed `./gradlew`, not a system Gradle. Debug APK path if you only assemble: `app/build/outputs/apk/debug/app-debug.apk`.

Do not declare UI work done until it is installed on this phone.

## New computer vs this phone

Debug APKs are signed with **this machine’s** `~/.android/debug.keystore`. A new PC cannot overwrite an APK from another PC.

```bash
adb -s R5CX12R1D9J uninstall app.benzpro
./gradlew :app:installDebug
```

Uninstall wipes app data (`adapter.json`, PID selection, etc.).

## Failures

| Symptom | What to do |
|---|---|
| `SDK location not found` | `local.properties` `sdk.dir=` or `ANDROID_HOME` |
| `validateSigningDebug` / `Cannot create directory /root/.android` | Not root; real `$HOME`. In Cursor, disable sandbox (`all`) |
| Compile OK, `adb: no devices` | USB debugging, RSA dialog, `-s R5CX12R1D9J` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Uninstall, then install (new debug keystore) |
| Device during `:app:installDebug` | `adb devices` first; unplug extra phones |
| CI green, phone not updated | CI does not install. Run `installDebug` here |

CI (`./gradlew :app:testDebugUnitTest :app:assembleDebug`) is not a substitute for installing on the S23.
