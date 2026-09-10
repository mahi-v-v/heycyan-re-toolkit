# HeyCyan tinker app

An [Expo](https://expo.dev) Router (React Native) companion app for the Cyan / HeyCyan smart
glasses. It talks to the glasses over BLE through a custom Expo native module
(`modules/sdk/`, package `expo.modules.glasssdk`) that wraps the vendor Android/iOS SDKs, and
exposes tabs for scanning, control, capture, OTA, and a raw debug console.

It is a **work-in-progress test bench**, not a finished product — it is used to exercise the
findings in the repository's [`docs/`](../docs/).

## Prerequisites

- Node.js LTS (v18+/v20+)
- JDK 21 + Android SDK (Android is the tested target)
- The **vendor SDK binaries**, which are not committed here — see
  [`../VENDOR_ASSETS.md`](../VENDOR_ASSETS.md).

## Run

```bash
npm install
npx expo prebuild        # generates android/ and links the custom native module
npx expo run:android     # builds & boots on a connected device/emulator
```

## Layout

- `app/(tabs)/` — the screens (scan / controls / capture / ota / debug).
- `modules/sdk/` — the custom Expo native module: Kotlin (`android/`), Swift/Obj-C (`ios/`),
  and the TypeScript bridge (`src/`). Per-vendor adapters live under
  `modules/sdk/*/glass/vendors/`.
- `app/(tabs)/ota.tsx` — OTA screen, including the wake-word enable/disable/query controls.
