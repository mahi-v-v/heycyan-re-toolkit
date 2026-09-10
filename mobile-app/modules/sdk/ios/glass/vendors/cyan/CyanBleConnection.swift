#if !targetEnvironment(simulator)
import Foundation
import CoreBluetooth
import QCSDK

/// Wraps QCCentralManager (ObjC) for Cyan glasses BLE connection.
/// Mirrors CyanBleConnection.kt.
class CyanBleConnection: NSObject, QCCentralManagerDelegate {

    // MARK: - Callbacks (set by CyanAdapter)

    var onConnectStateChange: ((Bool) -> Void)?
    var onServiceDiscovered: (() -> Void)?
    var onDeviceFound: ((String, String, Int) -> Void)?
    var onDataUpdateReport: (([String: Any]) -> Void)?

    // MARK: - State

    private var connectContinuation: CheckedContinuation<Void, Error>?
    private let connLock = NSLock()
    private var bleReadyContinuation: CheckedContinuation<Void, Error>?
    private let bleReadyLock = NSLock()
    private var serviceDiscoveredFired = false
    private var connectedPeripheral: CBPeripheral?

    /// Peripherals discovered during the last scan, keyed by deviceId (bare CBPeripheral UUID)
    private(set) var discoveredPeripherals: [String: CBPeripheral] = [:]

    func clearDiscoveredPeripherals() { discoveredPeripherals.removeAll() }

    // MARK: - Init

    func setup() {
        QCCentralManager.shared().autoReconnect = false
        QCCentralManager.shared().delegate = self

        // Listen for QCSDK data update reports (voice wakeup, etc.)
        NotificationCenter.default.addObserver(self, selector: #selector(handleDataUpdateReport(_:)),
            name: NSNotification.Name("QCDataUpdateReportNotification"), object: nil)
    }

    // MARK: - QCSDK data update reports

    @objc private func handleDataUpdateReport(_ notification: Notification) {
        guard let dict = notification.object as? [String: Any] else { return }
        print("[CyanBle] DataUpdateReport: \(dict)")
        onDataUpdateReport?(dict)
    }

    // MARK: - Scan

    func startScan() async throws {
        print("[CyanBle] startScan")

        // Wait for CBCentralManager to reach poweredOn (first-time init race).
        let currentState = QCCentralManager.shared().bleState
        if currentState != .poweredOn {
            print("[CyanBle] BLE not yet poweredOn (state=\(currentState.rawValue)), waiting...")
            try await withThrowingTaskGroupTimeout(nanoseconds: 5_000_000_000) {
                try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
                    self.bleReadyLock.withLock {
                        if QCCentralManager.shared().bleState == .poweredOn {
                            cont.resume(returning: ())
                        } else {
                            self.bleReadyContinuation = cont
                        }
                    }
                }
            }
            print("[CyanBle] BLE is now poweredOn, proceeding with scan")
        }

        QCCentralManager.shared().scan()
    }

    func stopScan() {
        print("[CyanBle] stopScan")
        let cont = bleReadyLock.withLock { () -> CheckedContinuation<Void, Error>? in
            let c = bleReadyContinuation; bleReadyContinuation = nil; return c
        }
        cont?.resume(throwing: CancellationError())
        QCCentralManager.shared().stopScan()
    }

    // MARK: - Connect

    func connect(peripheral: CBPeripheral) async throws {
        print("[CyanBle] connect: \(peripheral.identifier.uuidString)")
        // Re-register data report observer in case it was removed by a prior disconnect().
        // Remove first to prevent duplicates, then re-add.
        NotificationCenter.default.removeObserver(self,
            name: NSNotification.Name("QCDataUpdateReportNotification"), object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(handleDataUpdateReport(_:)),
            name: NSNotification.Name("QCDataUpdateReportNotification"), object: nil)
        serviceDiscoveredFired = false
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connLock.withLock { connectContinuation = cont }
            QCCentralManager.shared().connect(peripheral, timeout: 15, deviceType: .glasses)
        }
    }

    func disconnect() {
        print("[CyanBle] disconnect")
        serviceDiscoveredFired = false
        NotificationCenter.default.removeObserver(self, name: NSNotification.Name("QCDataUpdateReportNotification"), object: nil)
        QCCentralManager.shared().remove()
    }

    // MARK: - QCCentralManagerDelegate

    func didState(_ state: QCState) {
        print("[CyanBle] didState: \(state.rawValue)")
        switch state {
        case .connected:
            // QCSDKManager.addPeripheral fires onServiceDiscovered — wait for that
            // But we resolve the connect continuation here so connect() returns
            let cont = connLock.withLock { () -> CheckedContinuation<Void, Error>? in
                let c = connectContinuation; connectContinuation = nil; return c
            }
            cont?.resume(returning: ())
            onConnectStateChange?(true)

            // Guard against double-fire
            if !serviceDiscoveredFired {
                serviceDiscoveredFired = true
                onServiceDiscovered?()
            }

        case .disconnected, .unbind:
            serviceDiscoveredFired = false
            let cont = connLock.withLock { () -> CheckedContinuation<Void, Error>? in
                let c = connectContinuation; connectContinuation = nil; return c
            }
            if let cont {
                cont.resume(throwing: NSError(domain: "CyanBle", code: -2,
                    userInfo: [NSLocalizedDescriptionKey: "BLE disconnected"]))
            } else {
                onConnectStateChange?(false)
            }

        default:
            break
        }
    }

    func didBluetoothState(_ state: QCBluetoothState) {
        print("[CyanBle] didBluetoothState: \(state.rawValue)")
        if state == .poweredOn {
            let cont = bleReadyLock.withLock { () -> CheckedContinuation<Void, Error>? in
                let c = bleReadyContinuation; bleReadyContinuation = nil; return c
            }
            cont?.resume(returning: ())
        } else if state == .poweredOff || state == .unsupported || state == .unauthorized {
            let cont = bleReadyLock.withLock { () -> CheckedContinuation<Void, Error>? in
                let c = bleReadyContinuation; bleReadyContinuation = nil; return c
            }
            cont?.resume(throwing: NSError(domain: "CyanBle", code: -10,
                userInfo: [NSLocalizedDescriptionKey: "Bluetooth not available (state=\(state.rawValue))"]))
        }
    }

    func didScanPeripherals(_ peripheralArr: [QCBlePeripheral]) {
        for qcPeripheral in peripheralArr {
            let peripheral = qcPeripheral.peripheral
            let rawName = peripheral.name ?? ""
            guard isCyanDevice(rawName) else { continue }
            let displayName = rawName.hasPrefix("O_") ? String(rawName.dropFirst(2)) : rawName
            let deviceId = peripheral.identifier.uuidString // bare UUID (vendor is a separate field)
            discoveredPeripherals[deviceId] = peripheral
            print("[CyanBle] found: \(displayName) id=\(deviceId) rssi=\(qcPeripheral.rssi.intValue)")
            onDeviceFound?(deviceId, displayName, qcPeripheral.rssi.intValue)
        }
    }

    func didFailConnected(_ peripheral: CBPeripheral, error: Error?) {
        print("[CyanBle] didFailConnected: \(error?.localizedDescription ?? "unknown")")
        let cont = connLock.withLock { () -> CheckedContinuation<Void, Error>? in
            let c = connectContinuation; connectContinuation = nil; return c
        }
        cont?.resume(throwing: error ?? NSError(domain: "CyanBle", code: -3,
            userInfo: [NSLocalizedDescriptionKey: "Connection failed"]))
    }

    // MARK: - Device name filter (mirrors CyanBleConnection.kt)

    private func isCyanDevice(_ name: String) -> Bool {
        print("cyan device (name)")
        return name.lowercased().hasPrefix("cy")
    }
}
#endif // !targetEnvironment(simulator)
