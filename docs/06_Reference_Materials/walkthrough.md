# HeyCyan SDK Phase 1 Walkthrough

The goal of this phase was to thoroughly document, analyze, and reverse-engineer the HeyCyan Smart Glasses SDK (specifically for Android), as a precursor to integrating it into a React Native application. Firmware reverse engineering was explicitly excluded for now to maintain focus.

## What Was Accomplished

1. **Repository Cloning & Structure Analysis**
   - We cloned the SDK repository and identified the core `.aar` payload containing the compiled Java classes and proprietary BLE logic.
   - We extracted and decompiled the SDK's `classes.jar` using CFR to obtain the underlying source code for `com.oudmon.ble`, `com.glasses.sdk`, and `org.jvcompress`. The Wi-Fi transfer package `com.glasssutdio.wear.wifi` is NOT in the SDK jar — it was recovered separately by JADX-decompiling the main app APK.

2. **API & Endpoint Mapping**
   - Analyzed the SDK sample app (`MainActivity.kt`, `DeviceBindActivity.kt`) to trace the execution path of SDK commands.
   - We mapped the `LargeDataHandler` operations to specific byte array commands (e.g., `0x02, 0x01, 0x01` for taking a photo).
   - We deciphered the `WifiP2pManagerSingleton` workflow used to pull large media files off the glasses via a local HTTP server hosted by the glasses (`http://192.168.49.79/files/media.config` — note `192.168.49.79` is a sample placeholder; the real device IP is the dynamic Wi-Fi P2P group-owner address, typically `192.168.49.1`).

3. **Deliverables Created**
   All required deliverables were produced as artifacts for your review:
   - [Implementation Plan](./implementation_plan.md): Contains the SDK Architecture Documentation, Feature Matrix, BLE communication flow, and the React Native Integration Guide.
   - [API Reference](./api_reference.md): Contains exhaustive documentation on every exposed API, prerequisites, expected responses, and failure states.
   - [Testing Plan](./testing_plan.md): Provides a checklist for verifying connectivity, media controls, and WiFi transfers on real hardware.

## Next Steps

With the entire SDK completely documented and the React Native integration plan formulated, we are ready to move forward. The proposed architecture originally called for custom React Native Native Modules (`HeyCyanBleModule`, `HeyCyanMediaModule`) wrapping the Android SDK to handle the proprietary payloads efficiently. (Update: these two proposed modules were never built — the implemented React Native module is a single `GlassModule`.)

Once you approve the design decisions in the implementation plan, we can initialize the React Native project and begin writing the bridging code.
