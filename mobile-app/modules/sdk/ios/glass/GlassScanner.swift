import CoreBluetooth
import Foundation

/// Generic BLE scanner — one dedicated CBCentralManager that surfaces ALL nearby
/// BLE devices with their advertisement to JS. Vendor classification happens in JS
/// (OTA-updatable); this scanner does NO filtering. The vendor SDKs only handle
/// connect (re-acquiring their own CBPeripheral by UUID inside connectWithAutoScan).
///
/// Note: iOS only exposes the parsed advertisement dictionary (never the raw bytes),
/// and for some devices it never delivers the scan-response (e.g. the K900's
/// manufacturer company 0xB822 never reaches iOS — only its name does). So iOS
/// classification leans on whatever CoreBluetooth surfaces (name / service UUIDs /
/// service data). Android, which merges adv + scan-response, gets the full picture.
final class GlassScanner: NSObject, CBCentralManagerDelegate {
    static let shared = GlassScanner()
    private override init() { super.init() }

    private var central: CBCentralManager?
    private var wantScan = false
    /// deviceIds already reported this scan session (one event per device).
    private var reported = Set<String>()

    func start() {
        wantScan = true
        reported.removeAll()
        if central == nil {
            central = CBCentralManager(delegate: self, queue: nil,
                                       options: [CBCentralManagerOptionShowPowerAlertKey: false])
        } else if central?.state == .poweredOn {
            beginScan()
        }
    }

    func stop() {
        wantScan = false
        reported.removeAll()
        if central?.state == .poweredOn { central?.stopScan() }
    }

    private func beginScan() {
        central?.scanForPeripherals(withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]) // no filter = ALL devices
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn && wantScan { beginScan() }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let deviceId = peripheral.identifier.uuidString
        // One event per device per scan session.
        guard reported.insert(deviceId).inserted else { return }

        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name ?? ""

        var serviceUuids: [String] = []
        if let uuids = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] {
            serviceUuids = uuids.map { $0.uuidString.lowercased() }
        }

        var serviceData: [String: String] = [:]
        if let sd = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data] {
            for (uuid, data) in sd { serviceData[uuid.uuidString.lowercased()] = data.glassHex }
        }

        var manufacturerData: [String: String] = [:]
        if let mfg = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, mfg.count >= 2 {
            // First two bytes are the company id, little-endian.
            let company = UInt16(mfg[0]) | (UInt16(mfg[1]) << 8)
            let companyHex = String(format: "%04x", company)
            let payload = mfg.count > 2 ? mfg.subdata(in: 2..<mfg.count).glassHex : ""
            manufacturerData[companyHex] = payload
        }

        var advDict: [String: Any] = [
            "name": name,
            "serviceUuids": serviceUuids,
            "serviceData": serviceData,
            "manufacturerData": manufacturerData,
            // iOS does not expose the full raw advertisement byte stream.
            "rawAdvertisement": "",
        ]
        // BLE Appearance (AD type 0x19) — iOS exposes it under this (undocumented) key.
        // The K900 sets it to a firmware-stable, vendor-specific value (rename-proof).
        if let appearance = advertisementData["kCBAdvDataAppearance"] as? NSNumber {
            advDict["appearance"] = appearance.intValue
        }
        guard let json = try? JSONSerialization.data(withJSONObject: advDict),
              let jsonStr = String(data: json, encoding: .utf8) else { return }

        NotificationCenter.default.post(
            name: .glassEvent, object: nil,
            userInfo: ["msg": GlassEventMsg(
                cmd: GlassEventConstants.msgDeviceFound,
                n1: RSSI.intValue, s1: deviceId, s2: jsonStr)]
        )
    }
}

private extension Data {
    var glassHex: String { map { String(format: "%02x", $0) }.joined() }
}
