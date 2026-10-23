package com.example.sakhi.core.database

import android.content.ContentValues
import android.database.Cursor
import com.example.sakhi.core.model.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class ContactRepository(private val dbHelper: AppDatabaseHelper? = null) {

    private val _contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    open val contactsFlow: Flow<List<Contact>> = _contactsFlow.asStateFlow()

    init {
        if (dbHelper != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    refreshContacts()
                } catch (_: Exception) {}
            }
        }
    }

    suspend fun refreshContacts() = withContext(Dispatchers.IO) {
        val list = mutableListOf<Contact>()
        val db = dbHelper?.readableDatabase ?: return@withContext emptyList()
        val cursor: Cursor = db.query(
            AppDatabaseHelper.TABLE_CONTACTS,
            null,
            null,
            null,
            null,
            null,
            "${AppDatabaseHelper.COL_CONTACT_PRIORITY} ASC, ${AppDatabaseHelper.COL_CONTACT_ID} ASC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_CONTACT_ID)
            val nameIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_CONTACT_NAME)
            val phoneIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_CONTACT_PHONE)
            val relIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_CONTACT_RELATION)
            val prioIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_CONTACT_PRIORITY)
            val smsIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_CONTACT_SMS_ENABLED)

            while (it.moveToNext()) {
                list.add(
                    Contact(
                        id = it.getLong(idIdx),
                        name = it.getString(nameIdx),
                        phoneNumber = it.getString(phoneIdx),
                        relationship = it.getString(relIdx) ?: "",
                        priority = it.getInt(prioIdx),
                        smsEnabled = it.getInt(smsIdx) == 1
                    )
                )
            }
        }
        _contactsFlow.value = list
        list
    }

    suspend fun addContact(contact: Contact): Result<Long> = withContext(Dispatchers.IO) {
        if (!contact.isValid()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid contact name or phone number"))
        }

        val currentList = _contactsFlow.value
        if (currentList.size >= 5) {
            return@withContext Result.failure(IllegalStateException("Maximum of 5 contacts allowed"))
        }

        val cleanNewPhone = contact.phoneNumber.replace(Regex("[^0-9+]"), "")
        if (currentList.any { it.phoneNumber.replace(Regex("[^0-9+]"), "") == cleanNewPhone }) {
            return@withContext Result.failure(IllegalArgumentException("Contact phone number already exists"))
        }

        val db = dbHelper?.writableDatabase ?: return@withContext Result.failure(IllegalStateException("Database not available"))
        val values = ContentValues().apply {
            put(AppDatabaseHelper.COL_CONTACT_NAME, contact.name.trim())
            put(AppDatabaseHelper.COL_CONTACT_PHONE, contact.phoneNumber.trim())
            put(AppDatabaseHelper.COL_CONTACT_RELATION, contact.relationship.trim())
            put(AppDatabaseHelper.COL_CONTACT_PRIORITY, contact.priority.coerceIn(1, 5))
            put(AppDatabaseHelper.COL_CONTACT_SMS_ENABLED, if (contact.smsEnabled) 1 else 0)
        }

        val rowId = db.insert(AppDatabaseHelper.TABLE_CONTACTS, null, values)
        if (rowId != -1L) {
            refreshContacts()
            Result.success(rowId)
        } else {
            Result.failure(Exception("Failed to insert contact"))
        }
    }

    suspend fun updateContact(contact: Contact): Result<Unit> = withContext(Dispatchers.IO) {
        if (!contact.isValid()) {
            return@withContext Result.failure(IllegalArgumentException("Invalid contact data"))
        }

        val db = dbHelper?.writableDatabase ?: return@withContext Result.failure(IllegalStateException("Database not available"))
        val values = ContentValues().apply {
            put(AppDatabaseHelper.COL_CONTACT_NAME, contact.name.trim())
            put(AppDatabaseHelper.COL_CONTACT_PHONE, contact.phoneNumber.trim())
            put(AppDatabaseHelper.COL_CONTACT_RELATION, contact.relationship.trim())
            put(AppDatabaseHelper.COL_CONTACT_PRIORITY, contact.priority.coerceIn(1, 5))
            put(AppDatabaseHelper.COL_CONTACT_SMS_ENABLED, if (contact.smsEnabled) 1 else 0)
        }

        val rows = db.update(
            AppDatabaseHelper.TABLE_CONTACTS,
            values,
            "${AppDatabaseHelper.COL_CONTACT_ID} = ?",
            arrayOf(contact.id.toString())
        )
        if (rows > 0) {
            refreshContacts()
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to update contact"))
        }
    }

    suspend fun deleteContact(contactId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        val db = dbHelper?.writableDatabase ?: return@withContext Result.failure(IllegalStateException("Database not available"))
        val rows = db.delete(
            AppDatabaseHelper.TABLE_CONTACTS,
            "${AppDatabaseHelper.COL_CONTACT_ID} = ?",
            arrayOf(contactId.toString())
        )
        if (rows > 0) {
            refreshContacts()
            Result.success(Unit)
        } else {
            Result.failure(Exception("Contact not found"))
        }
    }

    open suspend fun getContacts(): List<Contact> = withContext(Dispatchers.IO) {
        if (dbHelper == null) return@withContext _contactsFlow.value
        refreshContacts()
        _contactsFlow.value
    }
}
