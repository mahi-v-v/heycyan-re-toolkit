# Vendor assets (not included in this repository)

This is a reverse-engineering and portfolio repository. It contains **only the author's own
work** — analysis, documentation, tooling scripts, and companion-app source. It intentionally
**does not redistribute** any third-party proprietary material, namely:

| Asset | What it is | Where the code/docs reference it |
|-------|------------|----------------------------------|
| Vendor Android SDK (`cyan_sdk.aar`, `cyan_sports_sdk.aar`, `k900_sdk.aar`) | Oudmon/HeyCyan BLE SDK | `mobile-app/modules/sdk/android/libs/` |
| Vendor iOS frameworks (`QCSDK.xcframework`, `CRPSmartGlasses.xcframework`, JieLi `JL_*`, …) | Vendor iOS SDK | `mobile-app/modules/sdk/ios/glass/vendors/` |
| Vendor source packages (`com.jieli.jl_audio_decode` Opus decoder, `com.android.mltcode` pay-certification) | Vendor-supplied Java sources the native module compiles against | `mobile-app/modules/sdk/android/src/main/java/` (referenced by `…/vendors/cyan_sports/CyanSportsAdapter.kt`) |
| Firmware images (`firmware.swu`, BLE-MCU `.bin`) | Device firmware | `hardware_research/` scripts + `docs/` |
| Decompiled vendor application | The vendor's companion app | referenced throughout `docs/` |

## Why they are excluded

These are copyrighted works owned by the device vendors and their chip suppliers, and their SDK
licenses generally prohibit redistribution. Publishing verbatim copies would be a redistribution
/ licensing issue independent of this project. The **analysis** of how they work is original work
and is included; the **binaries themselves** are not.

## How to obtain them

- **Vendor SDKs** ship to registered developers from the respective vendor; request them through
  the vendor's developer channel.
- **Firmware images** can be captured from the device's own OTA update flow (the mechanism is
  documented in [`docs/02_Firmware_and_OS.md`](docs/02_Firmware_and_OS.md)) or extracted from a
  device you own.
- To **build the companion app**, place the vendor Android `.aar` files in
  `mobile-app/modules/sdk/android/libs/` and the iOS frameworks under
  `mobile-app/modules/sdk/ios/glass/vendors/` as referenced by
  `mobile-app/modules/sdk/expo-module.config.json`, then run the build steps in the root README.

Nothing here needs those binaries in order to *read* the investigation — only to compile the app
or re-run the firmware tooling against real inputs.
