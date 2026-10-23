package com.example.sakhi.core.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.sakhi.core.model.AiState
import com.example.sakhi.core.model.Contact
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencySession
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.TimelineEvent
import com.example.sakhi.core.model.TimelineEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AppDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "sakhi_app.db"
        const val DATABASE_VERSION = 2

        // Table Contacts
        const val TABLE_CONTACTS = "contacts"
        const val COL_CONTACT_ID = "id"
        const val COL_CONTACT_NAME = "name"
        const val COL_CONTACT_PHONE = "phone_number"
        const val COL_CONTACT_RELATION = "relationship"
        const val COL_CONTACT_PRIORITY = "priority"
        const val COL_CONTACT_SMS_ENABLED = "sms_enabled"

        // Table Session History
        const val TABLE_SESSIONS = "emergency_sessions"
        const val COL_SESSION_ID = "session_id"
        const val COL_SESSION_START = "start_time"
        const val COL_SESSION_END = "end_time"
        const val COL_SESSION_OUTCOME = "outcome"
        const val COL_SESSION_LAT = "latitude"
        const val COL_SESSION_LNG = "longitude"
        const val COL_SESSION_ACCURACY = "accuracy"
        const val COL_SESSION_ADDRESS = "address"
        const val COL_SESSION_ALERT_STATE = "alert_state"
        const val COL_SESSION_POLICE_STATE = "police_state"
        const val COL_SESSION_AI_STATE = "ai_state"
        const val COL_SESSION_CANCEL_TIME = "cancel_time"
        const val COL_SESSION_TIMELINE = "timeline_json"
        const val COL_SESSION_TRANSCRIPTS = "transcripts_json"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createContactsTable = """
            CREATE TABLE $TABLE_CONTACTS (
                $COL_CONTACT_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_CONTACT_NAME TEXT NOT NULL,
                $COL_CONTACT_PHONE TEXT NOT NULL UNIQUE,
                $COL_CONTACT_RELATION TEXT,
                $COL_CONTACT_PRIORITY INTEGER NOT NULL DEFAULT 1,
                $COL_CONTACT_SMS_ENABLED INTEGER NOT NULL DEFAULT 1
            );
        """.trimIndent()

        val createSessionsTable = """
            CREATE TABLE $TABLE_SESSIONS (
                $COL_SESSION_ID TEXT PRIMARY KEY,
                $COL_SESSION_START INTEGER NOT NULL,
                $COL_SESSION_END INTEGER,
                $COL_SESSION_OUTCOME TEXT NOT NULL,
                $COL_SESSION_LAT REAL,
                $COL_SESSION_LNG REAL,
                $COL_SESSION_ACCURACY REAL,
                $COL_SESSION_ADDRESS TEXT,
                $COL_SESSION_ALERT_STATE TEXT NOT NULL,
                $COL_SESSION_POLICE_STATE TEXT NOT NULL,
                $COL_SESSION_AI_STATE TEXT NOT NULL,
                $COL_SESSION_CANCEL_TIME INTEGER,
                $COL_SESSION_TIMELINE TEXT,
                $COL_SESSION_TRANSCRIPTS TEXT
            );
        """.trimIndent()

        db.execSQL(createContactsTable)
        db.execSQL(createSessionsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE $TABLE_SESSIONS ADD COLUMN $COL_SESSION_TRANSCRIPTS TEXT;")
            } catch (_: Exception) {}
        }
    }
}
