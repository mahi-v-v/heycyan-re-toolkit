import Foundation

struct ApiError: Error, LocalizedError {
    let code: Int
    let message: String

    var errorDescription: String? {
        "[\(code)] \(message)"
    }
}

class ApiClient {
    static let shared = ApiClient()
    private init() {}

    private var baseUrl: String = ""
    private var headers: [String: String] = [:]
    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()

    func initialize(baseUrl: String) {
        self.baseUrl = baseUrl
    }

    func setHeader(_ key: String, value: String) { headers[key] = value }
    func removeHeader(_ key: String) { headers.removeValue(forKey: key) }
    func setAuthToken(_ token: String) { setHeader("Cookie", value: token) }
    func clearAuthToken() { removeHeader("Cookie") }

    func get<T: Codable>(endpoint: String) async throws -> T {
        let url = try buildUrl(endpoint)
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        let (data, response) = try await session.data(for: request)
        try validate(response: response, body: data)
        return try JSONDecoder().decode(T.self, from: data)
    }

    func post<T: Codable>(endpoint: String, body: some Encodable) async throws -> T {
        let url = try buildUrl(endpoint)
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        request.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await session.data(for: request)
        try validate(response: response, body: data)
        return try JSONDecoder().decode(T.self, from: data)
    }

    private func buildUrl(_ endpoint: String) throws -> URL {
        let cleanBase = baseUrl.hasSuffix("/") ? String(baseUrl.dropLast()) : baseUrl
        let cleanEndpoint = endpoint.hasPrefix("/") ? String(endpoint.dropFirst()) : endpoint
        guard let url = URL(string: "\(cleanBase)/\(cleanEndpoint)") else {
            throw ApiError(code: -1, message: "Invalid URL: \(cleanBase)/\(cleanEndpoint)")
        }
        return url
    }

    private func validate(response: URLResponse, body: Data) throws {
        guard let httpResponse = response as? HTTPURLResponse else { return }
        guard httpResponse.statusCode < 300 else {
            let bodyText = String(data: body, encoding: .utf8) ?? "<non-utf8 body>"
            throw ApiError(code: httpResponse.statusCode, message: "HTTP \(httpResponse.statusCode): \(bodyText)")
        }
    }
}
