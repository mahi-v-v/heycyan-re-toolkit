import Foundation
import CoreLocation

/// Gets device location (mirrors LocationTool.kt)
class LocationTool: NSObject, BaseTool, CLLocationManagerDelegate {

    let toolName = "getLocation"
    let requiredPermissions = ["location"]

    private var locationManager: CLLocationManager?
    private var locationContinuation: CheckedContinuation<CLLocation, Error>?
    private let continuationLock = NSLock()

    func hasPermissions() -> Bool {
        let status: CLAuthorizationStatus
        if #available(iOS 14.0, *) {
            status = CLLocationManager().authorizationStatus
        } else {
            status = CLLocationManager.authorizationStatus()
        }
        return status == .authorizedWhenInUse || status == .authorizedAlways
    }

    func execute(params: [String: Any]) async throws -> String {
        guard hasPermissions() else {
            return #"{"success":false,"error":"Location permission not granted"}"#
        }
        let highAccuracy = params["highAccuracy"] as? Bool ?? false
        let location = try await getCurrentLocation(highAccuracy: highAccuracy)

        var result: [String: Any] = [
            "success": true,
            "latitude": location.coordinate.latitude,
            "longitude": location.coordinate.longitude,
            "accuracy": location.horizontalAccuracy,
            "timestamp": location.timestamp.timeIntervalSince1970 * 1000
        ]
        if location.verticalAccuracy >= 0 { result["altitude"] = location.altitude }
        if location.speed >= 0           { result["speed"] = location.speed }
        if location.course >= 0          { result["bearing"] = location.course }

        let data = try JSONSerialization.data(withJSONObject: result)
        return String(data: data, encoding: .utf8) ?? #"{"success":false}"#
    }

    private func getCurrentLocation(highAccuracy: Bool) async throws -> CLLocation {
        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.main.async {
                let manager = CLLocationManager()
                manager.delegate = self
                manager.desiredAccuracy = highAccuracy
                    ? kCLLocationAccuracyBest
                    : kCLLocationAccuracyHundredMeters
                self.locationManager = manager
                self.continuationLock.withLock { self.locationContinuation = continuation }
                manager.requestLocation()
            }
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.first else { return }
        let cont = continuationLock.withLock { () -> CheckedContinuation<CLLocation, Error>? in
            let c = locationContinuation; locationContinuation = nil; return c
        }
        cont?.resume(returning: location)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        let cont = continuationLock.withLock { () -> CheckedContinuation<CLLocation, Error>? in
            let c = locationContinuation; locationContinuation = nil; return c
        }
        cont?.resume(throwing: error)
    }
}
