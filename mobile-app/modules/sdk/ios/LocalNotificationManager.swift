import Foundation

/// Shared concurrency helper used across the SDK module.
///
/// Note: this file previously hosted `LocalNotificationManager` (a persistent
/// "service active" notification mirroring Android's foreground service). That was
/// removed along with the dead BGTaskScheduler keep-alive code — iOS has no
/// foreground-service equivalent, so the notification served no purpose. The
/// `NSLock.withLock` helper below is still used throughout the module, so it lives on here.
extension NSLock {
    @discardableResult
    func withLock<T>(_ body: () -> T) -> T {
        lock()
        defer { unlock() }
        return body()
    }
}
