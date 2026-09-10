import Foundation
import Contacts

/// Searches device contacts (mirrors ContactsTool.kt)
class ContactsTool: BaseTool {

    let toolName = "searchContacts"
    let requiredPermissions = ["contacts"]

    func hasPermissions() -> Bool {
        return CNContactStore.authorizationStatus(for: .contacts) == .authorized
    }

    func execute(params: [String: Any]) async throws -> String {
        guard hasPermissions() else {
            return #"{"success":false,"error":"Contacts permission not granted"}"#
        }
        guard let query = params["query"] as? String, !query.isEmpty else {
            return #"{"success":false,"error":"'query' parameter is required"}"#
        }
        let limit = params["limit"] as? Int ?? 10
        let store = CNContactStore()
        let keysToFetch: [CNKeyDescriptor] = [
            CNContactIdentifierKey as CNKeyDescriptor,
            CNContactGivenNameKey as CNKeyDescriptor,
            CNContactFamilyNameKey as CNKeyDescriptor,
            CNContactPhoneNumbersKey as CNKeyDescriptor,
            CNContactEmailAddressesKey as CNKeyDescriptor
        ]
        let predicate = CNContact.predicateForContacts(matchingName: query)
        let contacts = try store.unifiedContacts(matching: predicate, keysToFetch: keysToFetch)
        let limited = Array(contacts.prefix(limit))
        let mapped: [[String: Any]] = limited.map { contact in
            let phones = contact.phoneNumbers.map { $0.value.stringValue }
            let emails = contact.emailAddresses.map { $0.value as String }
            return [
                "id": contact.identifier,
                "name": "\(contact.givenName) \(contact.familyName)"
                    .trimmingCharacters(in: .whitespaces),
                "phones": phones,
                "emails": emails
            ]
        }
        let result: [String: Any] = ["success": true, "contacts": mapped, "count": mapped.count]
        let data = try JSONSerialization.data(withJSONObject: result)
        return String(data: data, encoding: .utf8) ?? #"{"success":false}"#
    }
}
