# SDK Module — Project Info

## What This Is
Expo native module (`modules/sdk`) that bridges Android/iOS glass device SDKs to React Native/JS. Provides a vendor-agnostic API for smart glasses (photo, video, audio, BLE scan, file sync, etc).

---

## Current Work
Adding **Cyan** (HeyCyan) as a new Android glass vendor alongside the existing K900 vendor.

**Status:** Cyan vendor files created, imports fixed. Build not yet verified.

---

## Architecture

### Vendor Pattern
```
GlassModule (Expo JS bridge)
    ↓  vendor string decided in JS (src/util/detectVendor.ts)
GlassManager (singleton, pure vendor-string router)
    ↓ adapters["k900" | "cyan" | "cyan_sports" | else] — all built once in init()
BaseGlassAdapter (abstract base)
    ├── K900Adapter        — K900/KY glasses (k900_sdk.aar)
    ├── CyanAdapter        — Cyan/HeyCyan glasses (cyan_sdk.aar)
    ├── CyanSportsAdapter  — Cyan Sports / Moyoung CRP glasses (cyan_sports_sdk.aar)
    └── MockAdapter        — dev/testing
```

### Vendor Detection (JS: `src/util/detectVendor.ts`)
Vendor classification lives in **JS**, not native — so the rule can ship via an OTA JS
update with no native rebuild. `classifyVendor(adv, name)` keys off **BLE advertisement
markers** (device names are only a weak fallback since they are user-mutable). The native
scanner is generic: it surfaces every device's full advertisement to JS, which decides the
vendor and passes the resulting vendor **string** into `connect()`. `GlassManager` never
detects — it just routes the incoming string to the matching pre-built adapter.

| Vendor string | Advertisement markers |
|---|---|
| `k900` | Appearance 14667 (`0x394B`, the marker iOS exposes), mfg company `0xB822` (Android), or service UUID `0x4860` |
| `cyan_sports` | mfg company `0xF0EF` (Moyoung CRP) |
| `cyan` | service-data UUID `0x3802` / mfg company `0x1234` (Android), or mfg company `0x000E` (iOS + Android) |
| `unknown` | none matched → not routed to any real adapter (`connect()` throws) |

---

## Key Files

### Core (shared)
| File | Purpose |
|---|---|
| `android/src/.../glass/GlassAdapter.kt` | Interface all vendors must implement |
| `android/src/.../glass/BaseGlassAdapter.kt` | Abstract base — shared lifecycle hooks, EventBus, reconnect |
| `android/src/.../glass/GlassManager.kt` | Singleton — adapter pool (all built once in `init()`), pure vendor-string router, API routing |
| `android/src/.../glass/GlassEvent.kt` | Event constants, data classes (GlassEventMsg, states) |
| `android/src/.../glass/GlassModule.kt` | Expo module — JS bridge, event emission |
| `android/src/.../glass/GlassService.kt` | Foreground service — keeps connection alive |

### K900 Vendor (`vendors/k900/`)
| File | Purpose |
|---|---|
| `K900Adapter.kt` | Main adapter — CmdSendManager/CmdDealManager, request-response pattern |
| `K900BleConnection.kt` | BLE wrapper — custom UUIDs, BluetoothLEListener |
| `K900FileSyncManager.kt` | File sync — WiFi hotspot + socket |

### Cyan Vendor (`vendors/cyan/`) ← NEW
| File | Purpose |
|---|---|
| `CyanAdapter.kt` | Main adapter — LargeDataHandler commands, GlassesDeviceNotifyListener |
| `CyanBleConnection.kt` | BLE wrapper — QCBluetoothCallbackCloneReceiver, BleScannerHelper |
| `CyanFileSyncManager.kt` | File sync — HTTP download, setDeviceIp() must be called before sync |

### Cyan Sports Vendor (`vendors/cyan_sports/`)
| File | Purpose |
|---|---|
| `CyanSportsAdapter.kt` | Single-file adapter wrapping the Moyoung CRP SDK (`CRPBleClient → CRPBleDevice → CRPBleConnection`). Routed via JS marker `0xF0EF`. |

### AAR Libraries (`android/libs/`)
| File | Vendor |
|---|---|
| `k900_sdk.aar` | K900 SDK (package: `com.xy.ksdk.*`) |
| `cyan_sdk.aar` | Cyan/HeyCyan SDK (package: `com.oudmon.ble.base.*`) |
| `cyan_sports_sdk.aar` | Cyan Sports SDK (package: `com.moyoung.glasses.*`) |

---

## Cyan SDK Package Paths (com.oudmon.ble.base.*)
```kotlin
// BLE
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.bluetooth.DeviceManager

// Scan
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanWrapperCallback

// Data
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.communication.bigData.resp.BatteryResponse
import com.oudmon.ble.base.communication.bigData.resp.DeviceInfoResponse
```
- `GlassModelControlResponse` (`com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse`) — the concrete type delivered to the `glassesControl` callback. The callback signature hands back a base response, so `CyanAdapter` casts it (`baseResponse as? GlassModelControlResponse`) to read `dataType` / `errorCode` / `workTypeIng` / `glassWorkType` / `otaStatus`.
- Demo source: `hardware_research/decompilers_and_extracts/HeyCyanSmartGlassesSDK/android/GlassesSDKSample/` (repo-relative)

---

## Cyan SDK Key APIs

### Init sequence
```kotlin
BleBaseControl.getInstance(context).setmContext(app)
BleOperateManager.getInstance(context).setApplication(app)
BleOperateManager.getInstance().init()
// After init, register LocalBroadcastManager receiver with BleAction.getIntentFilter()
// After onServiceDiscovered: LargeDataHandler.getInstance().initEnable()
```

### Connection
```kotlin
BleOperateManager.getInstance().connectDirectly(macAddress)  // connect
BleOperateManager.getInstance().unBindDevice()               // disconnect
BleOperateManager.getInstance().isConnected                  // Boolean property
```

### BLE Scan
```kotlin
BleScannerHelper.getInstance().scanDevice(activity, null, scanWrapperCallback)
BleScannerHelper.getInstance().stopScan(activity)
BleScannerHelper.getInstance().reSetCallback()
// ScanWrapperCallback.onLeScan(device, rssi, scanRecord)
```

### QCBluetoothCallbackCloneReceiver overrides
```kotlin
override fun connectStatue(device: BluetoothDevice?, connected: Boolean)
override fun onServiceDiscovered()
override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?)
override fun onCharacteristicRead(uuid: String?, data: ByteArray?)
```

### Commands (glassesControl)
```kotlin
LargeDataHandler.getInstance().glassesControl(byteArrayOf(...)) { _, baseResponse ->
    val response = baseResponse as? GlassModelControlResponse
    response?.dataType     // Int
    response?.errorCode    // Int (CyanAdapter treats dataType==1 && errorCode==-1 as success)
    response?.workTypeIng  // Int — current work mode
    response?.imageCount   // Int
    response?.videoCount   // Int
    response?.recordCount  // Int
}
```
| Command | Bytes |
|---|---|
| Take photo | `0x02, 0x01, 0x01` |
| Start video | `0x02, 0x01, 0x02` |
| Stop video | `0x02, 0x01, 0x03` |
| Start audio | `0x02, 0x01, 0x08` |
| Stop audio | `0x02, 0x01, 0x0c` |
| AI mode (capture) | `0x02, 0x01, 0x06` |
| Cov-living / real-time | `0x02, 0x01, 0x07` |
| Media count | `0x02, 0x04` |

### Notifications (GlassesDeviceNotifyListener)
```kotlin
LargeDataHandler.getInstance().addOutDeviceListener(id, listener)
// override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp)
// response.loadData — ByteArray
// response.loadData[6] — notification type byte
//   0x05 = battery  (loadData[7]=level, loadData[8]=charging)
//   0x02 = AI result
//   0x03 = mic/voice wakeup
```

### Battery
```kotlin
LargeDataHandler.getInstance().addBatteryCallBack("key") { _, response ->
    response?.battery        // Int  (Kotlin `.battery` = Java getter `getBattery()`)
    response?.isCharging     // Boolean
}
LargeDataHandler.getInstance().syncBattery()
```

### Device info
```kotlin
LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
    response?.firmwareVersion
    response?.wifiFirmwareVersion
    response?.hardwareVersion
    response?.wifiHardwareVersion
}
```

### AI image thumbnail
```kotlin
LargeDataHandler.getInstance().getPictureThumbnails { cmdType, isComplete, data ->
    // data: ByteArray? — arrives in chunks; append each non-empty chunk to a buffer.
    // isComplete: Boolean — true on the final chunk; assemble the JPG and save then.
}
```

---

## Event System
All adapters post to `EventBus` using `GlassEventMsg(cmd, n1, n2, s1, s2)`.
`GlassModule` subscribes and forwards to JS via `sendEvent()`.

| Constant | Value | Payload |
|---|---|---|
| MSG_GLASS_DEVICE_FOUND | 70001 | s1=deviceId, s2=name, n1=rssi |
| MSG_GLASS_STATE_CHANGED | 70002 | n1=oldState, n2=newState, s1=reason |
| MSG_GLASS_CONNECTED | 70003 | s1=deviceId, s2=name |
| MSG_GLASS_DISCONNECTED | 70004 | s1=deviceId, s2=reason, n1=expected |
| MSG_GLASS_BATTERY | 70005 | n1=level, n2=charging |
| MSG_GLASS_ERROR | 70006 | s1=message, n1=fatal |
| MSG_GLASS_READY | 70009 | — |
| MSG_GLASS_SYNC_STARTED | 70010 | — |
| MSG_GLASS_SYNC_PROGRESS | 70011 | n1=current, n2=total |
| MSG_GLASS_SYNC_COMPLETED | 70012 | n1=files, n2=success, s1=bytes |
| MSG_GLASS_WEAR_STATUS | 70013 | n1=worn |
| MSG_GLASS_VOICE_WAKEUP | 70014 | — |
| MSG_GLASS_BT_STATUS | 70015 | n1=notify subtype (multiplexed, NOT "connected"), n2=subtype payload |

`MSG_GLASS_BT_STATUS` is a multiplexed channel for device notifications; `n1` carries the
large-data notify subtype byte, not a connected flag: `0x04`=OTA firmware progress (n2=%),
`0x0c`=pause, `0x0d`=unbind requested, `0x0e`=memory low, `0x10`=translation pause,
`0x12`=volume (n2=level).

---

## Active Adapter (no default)
There is **no default adapter**. `init()` builds every vendor adapter (`k900`, `cyan`,
`cyan_sports`) once into a persistent pool; `activeVendor` is `null` until
`connect(deviceId, vendor)` sets it from the JS-supplied vendor string. Scanning does not
need an active adapter — it runs through one generic `GlassScanner`. (There is no
`ensureAdapterForScanning()` method, and CYAN is not a fallback.)

---

## Known TODOs / Open Items
- `CyanFileSyncManager.setDeviceIp(ip)` must be called before `syncFiles()` — IP comes from BLE characteristic change (`onCharacteristicChange` in `QCBluetoothCallbackCloneReceiver`). Wire up in `CyanBleConnection` or `CyanAdapter`.
- `BatteryResponse` fields are `battery` (Kotlin `.battery` = Java `getBattery()`) and `isCharging` — as used in `CyanAdapter`. (End-to-end battery read still UNVERIFIED — needs hardware/dynamic test.)
- iOS Cyan implementation not started.
- `setMediaConfig` / `getMediaConfig` not supported by Cyan SDK — returns stub/false.
