package expo.modules.glasssdk.tools

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import expo.modules.glasssdk.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contacts tool for searching and retrieving contacts
 *
 * Request params:
 * {
 *   "query": "John",     // Search query (name, phone, email)
 *   "limit": 10          // Optional, max results, default 10
 * }
 *
 * Response:
 * {"success":true,"contacts":[{"id":"123","name":"John Doe","phones":["+1234567890"],"emails":["john@example.com"]}],"count":1}
 * or
 * {"success":false,"error":"Contacts permission denied"}
 */
class ContactsTool(context: Context) : BaseTool(context) {

    override val toolName = "searchContacts"

    override val requiredPermissions = arrayOf(
        Manifest.permission.READ_CONTACTS
    )

    override suspend fun execute(params: JSONObject): String {
        return try {
            // Validate parameters
            if (!params.has("query") || params.optString("query").isBlank()) {
                return """{"success":false,"error":"'query' parameter is required"}"""
            }

            val query = params.getString("query")
            val limit = params.optInt("limit", 10)

            // Search contacts
            val contacts = searchContacts(query, limit)

            // Build response
            val response = JSONObject().apply {
                put("success", true)
                put("contacts", contacts)
                put("count", contacts.length())
            }

            response.toString()

        } catch (e: SecurityException) {
            AppLog.e("ContactsTool: Permission denied", e)
            """{"success":false,"error":"Contacts permission denied"}"""

        } catch (e: Exception) {
            AppLog.e("ContactsTool: Failed to search contacts", e)
            """{"success":false,"error":"${e.message ?: "Failed to search contacts"}"}"""
        }
    }

    /**
     * Search contacts by name, phone, or email
     */
    private suspend fun searchContacts(query: String, limit: Int): JSONArray =
        withContext(Dispatchers.IO) {
            val contacts = JSONArray()
            val contactMap = mutableMapOf<String, JSONObject>()

            // Query contacts
            val projection = arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.DISPLAY_NAME,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                ContactsContract.CommonDataKinds.Email.TYPE
            )

            val selection = "${ContactsContract.Data.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.Data.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val nameIndex = cursor.getColumnIndex(ContactsContract.Data.DISPLAY_NAME)
                val mimeTypeIndex = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val phoneIndex =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val phoneTypeIndex =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                val emailIndex =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                val emailTypeIndex =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.TYPE)

                while (cursor.moveToNext() && contactMap.size < limit) {
                    val id = cursor.getString(idIndex) ?: continue
                    val name = cursor.getString(nameIndex) ?: "Unknown"
                    val mimeType = cursor.getString(mimeTypeIndex) ?: continue

                    // Get or create contact object
                    val contact = contactMap.getOrPut(id) {
                        JSONObject().apply {
                            put("id", id)
                            put("name", name)
                            put("phones", JSONArray())
                            put("emails", JSONArray())
                        }
                    }

                    // Add phone or email based on MIME type
                    when (mimeType) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            val number = cursor.getString(phoneIndex)
                            if (number != null) {
                                contact.getJSONArray("phones").put(number)
                            }
                        }

                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            val email = cursor.getString(emailIndex)
                            if (email != null) {
                                contact.getJSONArray("emails").put(email)
                            }
                        }
                    }
                }
            }

            // Convert map to array
            contactMap.values.forEach { contacts.put(it) }

            return@withContext contacts
        }
}
