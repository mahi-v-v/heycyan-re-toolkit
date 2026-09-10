# Smart Glasses Communication Protocol Specification

This document details the reverse-engineered communication protocols used by the smart glasses, covering the BLE command interface, packet structures, known opcodes, and the dual-firmware OTA update mechanisms.

## 1. System Architecture

The glasses feature a dual-chip architecture, utilizing both Bluetooth Low Energy (BLE) and Wi-Fi Direct (P2P) for communication.
- **BLE Channel**: Used as a low-bandwidth control and management plane. All commands, status queries, and AI voice triggers happen here.
- **Wi-Fi Channel**: Used as a high-bandwidth data plane. Photos, videos, and Main SoC firmware updates are transferred over Wi-Fi.

## 2. BLE Transport & Packet Framing

The device exposes a proprietary command/notify interface over its custom **SERIAL_PORT** service. All `0xBC`-wrapped commands and notifications flow over this service.

*   **Service UUID:** `de5bf728-d711-4e47-af26-65e3012a5dc7`
*   **Write (TX) UUID:** `de5bf72a-d711-4e47-af26-65e3012a5dc7`
*   **Notify (RX) UUID:** `de5bf729-d711-4e47-af26-65e3012a5dc7`

> **Note:** The Nordic-style UUID `6e40fff0-b5a3-f393-e0a9-e50e24dcca9e` (with `6e400002`/`6e400003` characteristics) is a **defined-but-unused legacy band service** — it is declared in the codebase but is NOT the transport used for glasses command/notify traffic. Do not use it.

### 2.1 Application Layer Packet Format

Commands sent to the glasses and responses received from the glasses are wrapped in a 6-byte header.

| Offset | Size | Name | Description |
| :--- | :--- | :--- | :--- |
| 0 | 1 byte | Magic | Always `0xBC` (188 in unsigned decimal, -68 in signed byte). |
| 1 | 1 byte | Opcode | The Command ID (e.g., `0x43` for Device Info). |
| 2 | 2 bytes | Length | Little-Endian 16-bit integer representing the payload size. |
| 4 | 2 bytes | CRC16 | Little-Endian 16-bit CRC of the payload. If payload is empty (Length=0), this is set to `0xFFFF`. |
| 6 | N bytes | Payload | The command/response data bytes. |

### 2.2 Response Fragmentation

Because BLE MTU sizes are limited (often 20-23 bytes by default), responses larger than the MTU are fragmented. The receiver must implement a buffer:
1. Verify the incoming packet begins with `0xBC`.
2. Extract the `Length` field.
3. If the total received bytes (minus the 6-byte header) is less than `Length`, buffer the payload and wait for the next BLE notification.
4. Concatenate subsequent notifications until the buffer reaches the expected `Length`.

*Note: The official SDK parser calculates CRC on outbound packets but ignores it on incoming packets, relying entirely on the Length field for reassembly.*

---

## 3. Opcode Reference Table

The following opcodes are dispatched inside the `0xBC` wrapper.

| Opcode (Hex) | Decimal | Payload | Description |
| :--- | :--- | :--- | :--- |
| `0x2E` | 46 | Raw bytes | Sync Classic Bluetooth state. |
| `0x40` | 64 | 9-byte BCD time struct | Sync phone time to the glasses. Payload is a 9-byte BCD-encoded date/time structure (year/month/day/hour/minute/second/etc.), NOT `[0x01]`. |
| `0x41` | 65 | Raw bytes | Base glasses control (e.g., trigger photo, video, media count). |
| `0x42` | 66 | `[0x00, 0x00]` | Fetch battery percentage and charging status. |
| `0x43` | 67 | `[0x00, 0x00]` | Fetch firmware and hardware version strings. |
| `0x44` | 68 | `[0x01, 0x00]` or `[0x02, state]` | Triggers the AI voice assistant microphone on the glasses. |
| `0x45` | 69 | Raw bytes | Sync Heartbeat (connection keep-alive). |
| `0x46` | 70 | `[0x01, 0x00]` | Wear Check. Queries proximity sensor to check if glasses are worn. |
| `0x47` | 71 | `[0x01, 0x00]` | Wear Function Support. Queries supported touch/sensor capabilities. |
| `0x48` | 72 | Raw bytes | Triggers AI audio playback / streaming state. |
| `0x49` | 73 | `[0x02, 0x01]` | Open BT. Forces Classic Bluetooth on/pairing mode (for A2DP audio). |
| `0x51` | 81 | `[0x02, 0x01, min, max...]`| Set/Get Volume Control limits for system and media. |
| `0x52` | 82 | Raw bytes | Speak Sound Switch (toggles system shutter/beeps). |
| `0xFC` | 252 | UTF-8 String URL | Write IP to SoC. Instructs the glasses to connect to the provided URL (used for Wi-Fi P2P downloads and OTA). |
| `0xFD` | 253 | Raw bytes | Get Picture Thumbnails. Fetches small preview images over BLE before a full Wi-Fi sync. |
| `0x0F` | 15 | `[0x00, 0x00]` | **Defined-but-unused legacy constant** ("enter OTA / reboot bootloader"). It is declared in the codebase but is NOT used by the BLE DFU flow — the AM01CY `.bin` update stays on the same `de5bf728` service without any bootloader-mode switch (see §4.2). Do not rely on this opcode. |

---

## 4. OTA Firmware Update Mechanisms

Because of the dual-chip architecture, there are two entirely different firmware update sequences.

### 4.1 Main SoC Firmware Update (Wi-Fi)

The Main Processor (SoC) runs a heavier OS and updates via `.swu` images over Wi-Fi.

1. **Download**: The companion app downloads the `.swu` firmware from: 
   `https://qcwxfactory.oss-cn-beijing.aliyuncs.com/bin/glasses/{hwVersionWifi}.swu`
2. **Host**: The app starts a local HTTP server (`java.net.ServerSocket`) on the phone, typically on port `8080`.
3. **P2P Setup**: The phone enables Wi-Fi Direct (P2P) and forms a hotspot.
4. **Trigger**: The phone sends Opcode `0xFC` (`writeIpToSoc`) over BLE with the payload: 
   `http://[Phone_IP]:8080/firmware.swu`
5. **Fetch**: The glasses connect to the Wi-Fi hotspot, perform a standard HTTP GET, download the `.swu` file internally, and apply the update.

### 4.2 AM01CY BLE MCU Firmware Update (BLE DFU)

The AM01CY BLE MCU updates its firmware via a custom DFU protocol carried **inside the normal `0xBC` command wrapper**, over the **same `de5bf728` SERIAL_PORT service** used for all other command/notify traffic. There is **no bootloader-mode switch, no reboot into a separate bootloader, and no new/secondary GATT service** — the DFU opcodes are dispatched exactly like any other command. It does not use standard protocols such as Nordic Secure DFU.

> **Correction (supersedes earlier drafts):** Earlier revisions claimed the phone first sends opcode `0x0F` to reboot the device into a bootloader that advertises a *new* service, and that the `0xBC` wrapper is then *discarded* for raw DFU. Both claims are wrong. The `0xBC` header is **retained** around DFU opcodes `1`–`5`, and the transfer happens on the existing `de5bf728` service with no mode transition. Opcode `0x0F` is a defined-but-unused legacy constant (see §3).

The DFU opcodes (`1`–`5`) are each wrapped in the standard `0xBC` header (Magic `0xBC`, Opcode, Length, CRC16, Payload) described in §2.1:

1. **Start**: Phone sends DFU opcode `1` (Start), `0xBC`-wrapped.
2. **Header / Metadata**: Phone sends DFU opcode `2` with the file metadata — `FileLength(4 bytes)`, `CRC16(2 bytes)`, and the **whole-file checksum, which is a 16-bit additive sum** (the low 16 bits of the arithmetic sum of every byte in the `.bin` file), NOT a mod-256/8-bit checksum.
3. **Data Slicing**: The phone reads the `.bin` file and chunks it into fixed-size data blocks, sending DFU opcode `3` with `BlockIndex` and the block `Data` for each chunk.
   - The **app manually chunks the writes** to fit the negotiated BLE MTU/write size — it is NOT relying on the OS to auto-fragment a large payload. The application code slices each block and issues the individual GATT writes itself.
4. **Check**: Phone sends DFU opcode `4` (Check). The MCU verifies the accumulated 16-bit additive checksum against the value from step 2.
5. **End & Reboot**: Phone sends DFU opcode `5` (End). The MCU commits the new firmware and reboots into normal mode.
