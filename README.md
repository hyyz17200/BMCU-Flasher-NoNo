<h1 align="center">Support</h1>

<p align="center">
  Bambu Lab continues tightening compatibility around BMCU, and there is a growing risk that BMCU may eventually become unusable in that ecosystem.
</p>

<p align="center">
  To prepare for that, I want to build BMCU support for open-source Klipper-based printers.
</p>

<p align="center">
  I am currently raising funds to buy a test printer for this work.
</p>

<p align="center">
  The $500 goal does not need to be reached in full. If I manage to save the remaining amount myself, I will cover the rest out of my own pocket.
</p>

<p align="center">
  <a href="https://ko-fi.com/jarczakpawel/goal?g=0">
    <img src="./banner-klipper.png" alt="Want BMCU on Klipper? Click the links below to support development." width="460">
  </a>
</p>

<p align="center">
  <a href="https://ko-fi.com/jarczakpawel/goal?g=0"><strong>Support on Ko-fi</strong></a>
  ·
  <a href="https://revolut.me/paweqxdkx"><strong>Support via Revolut</strong></a>
</p>

<p align="center">
  Direct Revolut support avoids Ko-fi fees, so more of your contribution goes directly to the project.
</p>

# BMCU Flasher

Cross-platform flasher for BMCU (WCH ISP protocol).

![GUI](gui.jpg)

- OS: Linux / Windows / macOS / Android
- Modes:
  - USB (BMCU with CH340 on board, AutoDI)
  - TTL (pin header / external USB-Serial, manual BOOT + RESET)
- Android:
  - USB (CH340 + USB OTG)
  - TTL (external USB-Serial, manual BOOT + RESET)
  - Online firmware or custom local `.bin` files (system file picker)
  - App language follows system language

## Download
Download prebuilt binaries from Releases.

Main builds:
- Windows x64: BMCU-Flasher-windows-x64.zip
- Windows x86: BMCU-Flasher-windows-x86.zip
- macOS Apple Silicon (arm64): BMCU-Flasher-macos-arm64.zip
- macOS Intel (x86_64): BMCU-Flasher-macos-x86_64.zip
- Linux x64: BMCU-Flasher-linux-x64.tar.gz
- Linux arm64: BMCU-Flasher-linux-arm64.tar.gz
- Android APK: BMCU-Flasher-android.apk

Legacy / experimental / community (older OS, best-effort):
- Windows legacy (older Windows): BMCU-Flasher-windows-legacy-x64.zip, BMCU-Flasher-windows-legacy-x86.zip
- macOS Catalina 10.15 (Intel): BMCU-Flasher-catalina-1.2.1.zip (community build by Doyle4, tested on Catalina)
- macOS legacy try (CI attempt): BMCU-Flasher-macos-x86_64-legacy-try.zip (may not run on Catalina)

## Android requirements
- Android 5.0+ (API 21+) (minSdk=21)
- USB OTG + USB host mode
- CH340 supported for USB mode
- External USB-Serial adapters supported for TTL mode
- Do NOT plug USB or flash while BMCU is connected to the printer

## Firmware
Latest BMCU firmware:
https://github.com/jarczakpawel/BMCU-C-PJARCZAK

GUI has "Online" firmware selection (no manual searching/downloading):
- choose mode (Standard / Soft load / High force)
- choose slot (SOLO / AMS_A / AMS_B / AMS_C / AMS_D)
- choose retract length, AUTOLOAD, RGB
- the app downloads the selected firmware and flashes it

## Drivers (CH340)
In USB mode (CH340), drivers may be required on Windows.
Linux and macOS usually work out-of-the-box.

## Usage
GUI (recommended):
- Run the app
- Click "Online" to pick the exact firmware variant (mode / slot / retract / autoload / RGB)
- Click "Help" inside the app (wiring + BOOT/RESET steps)

Android:
- Install APK
- Choose USB or TTL mode
- USB mode: plug BMCU via USB OTG
- TTL mode: connect external USB-Serial adapter and enter BOOTloader manually
- Choose the firmware source: online options or **Local BIN file**
- For a local BIN, tap **Choose .bin file** and check the filename, size and SHA-256
- Click "Flash"

Local BIN mode works with both USB and TTL and does not download firmware. The
selected bytes are held as a snapshot; online force/slot/retract/RGB/autoload
settings do not modify the file. Cancelling the picker keeps the previous
selection; a failed import clears it. The original document is never deleted.
Empty files and images above 65,520 bytes are rejected before opening the serial
port: the existing 56-byte ISP transfers must fit in the board's 64 KiB flash.
This size check does not identify whether a firmware image matches your board.

CLI (optional):
- USB (auto port by VID/PID):
```bash
python3 bmcu_flasher.py firmware.bin --mode usb
```
- TTL (manual BOOT+RESET, port required):
```bash
  python3 bmcu_flasher.py firmware.bin --mode ttl --port /dev/ttyUSB0
```

## Building Android

Use JDK 17 or newer, Android SDK platform 35 and the checked-in Gradle 9.1.0 wrapper.
Set `ANDROID_HOME` to your SDK directory (or use `android/local.properties`).

```powershell
cd android
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

On Linux/macOS use `bash ./gradlew` instead. The APK is written to
`android/app/build/outputs/apk/release/app-release.apk`.

GitHub Actions: **Android APK** builds on Android/i18n changes to `main`, checks
pull requests, and supports **Run workflow**. Download the APK and SHA-256 from the
`BMCU-Flasher-NoNo-android` artifact; test/lint reports are a separate artifact.
The existing `v*` tag workflow also builds the APK alongside desktop releases.

These are test APKs signed with the builder's debug key, as in the original
project. Local and GitHub builds can have different signing certificates; if
Android reports an incompatible signature, uninstall the previous test app
before installing (this clears its app data). Stable release signing requires
a persistent private signing key and is not configured here.

## License
MIT - see LICENSE.
