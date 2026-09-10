import Foundation

class SdkConfig {
    static let shared = SdkConfig()
    private init() {}

    private let defaultApiUrl = "https://api.example.com"

    func initialize() {}

    var apiUrl: String {
        return Bundle.main.infoDictionary?["apiUrl"] as? String ?? defaultApiUrl
    }
}
