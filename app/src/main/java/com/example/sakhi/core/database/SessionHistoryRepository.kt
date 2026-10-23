package com.example.sakhi.core.database

import android.content.ContentValues
import android.database.Cursor
import com.example.sakhi.core.model.AiState
import com.example.sakhi.core.model.ContactAlertState
import com.example.sakhi.core.model.EmergencySession
import com.example.sakhi.core.model.PoliceState
import com.example.sakhi.core.model.TimelineEvent
import com.example.sakhi.core.model.TimelineEventType
import com.example.sakhi.core.model.TranscriptSegment
import com.example.sakhi.data.assistant.ExternalSpeechLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

open class SessionHistoryRepository(private val dbHelper: AppDatabaseHelper? = null) {

    private val _sessionsFlow = MutableStateFlow<List<EmergencySession>>(emptyList())
    open val sessionsFlow: Flow<List<EmergencySession>> = _sessionsFlow.asStateFlow()

    suspend fun refreshSessions() = withContext(Dispatchers.IO) {
        val list = mutableListOf<EmergencySession>()
        val db = dbHelper?.readableDatabase ?: return@withContext emptyList()
        val cursor: Cursor = db.query(
            AppDatabaseHelper.TABLE_SESSIONS,
            null,
            null,
            null,
            null,
            null,
            "${AppDatabaseHelper.COL_SESSION_START} DESC"
        )
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_ID)
            val startIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_START)
            val endIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_END)
            val outcomeIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_OUTCOME)
            val latIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_LAT)
            val lngIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_LNG)
            val accIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_ACCURACY)
            val addrIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_ADDRESS)
            val alertIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_ALERT_STATE)
            val policeIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_POLICE_STATE)
            val aiIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_AI_STATE)
            val cancelIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_CANCEL_TIME)
            val timelineIdx = it.getColumnIndexOrThrow(AppDatabaseHelper.COL_SESSION_TIMELINE)
            val transcriptsIdx = it.getColumnIndex(AppDatabaseHelper.COL_SESSION_TRANSCRIPTS)

            while (it.moveToNext()) {
                val timelineJson = it.getString(timelineIdx) ?: "[]"
                val timelineList = parseTimelineJson(timelineJson)
                val transcriptsJson = if (transcriptsIdx >= 0 && !it.isNull(transcriptsIdx)) it.getString(transcriptsIdx) else null
                val (hindiTranscripts, englishTranscripts) = parseTranscriptsJson(transcriptsJson)

                val latVal = if (it.isNull(latIdx)) null else it.getDouble(latIdx)
                val lngVal = if (it.isNull(lngIdx)) null else it.getDouble(lngIdx)
                val accVal = if (it.isNull(accIdx)) null else it.getFloat(accIdx)
                val cancelVal = if (it.isNull(cancelIdx)) null else it.getLong(cancelIdx)
                val endVal = if (it.isNull(endIdx)) null else it.getLong(endIdx)

                list.add(
                    EmergencySession(
                        id = it.getString(idIdx),
                        startTime = it.getLong(startIdx),
                        endTime = endVal,
                        outcome = it.getString(outcomeIdx),
                        latitude = latVal,
                        longitude = lngVal,
                        locationAccuracy = accVal,
                        locationAddress = it.getString(addrIdx),
                        contactAlertState = try {
                            ContactAlertState.valueOf(it.getString(alertIdx))
                        } catch (e: Exception) {
                            ContactAlertState.NOT_STARTED
                        },
                        policeState = try {
                            PoliceState.valueOf(it.getString(policeIdx))
                        } catch (e: Exception) {
                            PoliceState.NOT_REQUESTED
                        },
                        aiState = try {
                            AiState.valueOf(it.getString(aiIdx))
                        } catch (e: Exception) {
                            AiState.OFF
                        },
                        cancellationTimestamp = cancelVal,
                        timeline = timelineList,
                        hindiTranscripts = hindiTranscripts,
                        englishTranscripts = englishTranscripts
                    )
                )
            }
        }
        _sessionsFlow.value = list
        list
    }

    open suspend fun saveSession(session: EmergencySession) = withContext(Dispatchers.IO) {
        val helper = dbHelper ?: return@withContext
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put(AppDatabaseHelper.COL_SESSION_ID, session.id)
            put(AppDatabaseHelper.COL_SESSION_START, session.startTime)
            put(AppDatabaseHelper.COL_SESSION_END, session.endTime)
            put(AppDatabaseHelper.COL_SESSION_OUTCOME, session.outcome)
            put(AppDatabaseHelper.COL_SESSION_LAT, session.latitude)
            put(AppDatabaseHelper.COL_SESSION_LNG, session.longitude)
            put(AppDatabaseHelper.COL_SESSION_ACCURACY, session.locationAccuracy)
            put(AppDatabaseHelper.COL_SESSION_ADDRESS, session.locationAddress)
            put(AppDatabaseHelper.COL_SESSION_ALERT_STATE, session.contactAlertState.name)
            put(AppDatabaseHelper.COL_SESSION_POLICE_STATE, session.policeState.name)
            put(AppDatabaseHelper.COL_SESSION_AI_STATE, session.aiState.name)
            put(AppDatabaseHelper.COL_SESSION_CANCEL_TIME, session.cancellationTimestamp)
            put(AppDatabaseHelper.COL_SESSION_TIMELINE, serializeTimelineJson(session.timeline))
            put(AppDatabaseHelper.COL_SESSION_TRANSCRIPTS, serializeTranscriptsJson(session.hindiTranscripts, session.englishTranscripts))
        }

        db.insertWithOnConflict(
            AppDatabaseHelper.TABLE_SESSIONS,
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        refreshSessions()
    }

    suspend fun getSessions(): List<EmergencySession> = withContext(Dispatchers.IO) {
        if (_sessionsFlow.value.isEmpty()) {
            refreshSessions()
        }
        _sessionsFlow.value
    }

    fun exportDiagnosticLog(sessions: List<EmergencySession>): String {
        val jsonArray = JSONArray()
        for (session in sessions) {
            val sessionObj = JSONObject().apply {
                put("session_id", session.id)
                put("start_time", session.startTime)
                put("end_time", session.endTime ?: JSONObject.NULL)
                put("duration_ms", session.durationMillis)
                put("outcome", session.outcome)
                put("alert_state", session.contactAlertState.name)
                put("police_state", session.policeState.name)
                put("ai_state", session.aiState.name)
                put("has_location", session.latitude != null)
                val timelineArr = JSONArray()
                session.timeline.forEach { event ->
                    timelineArr.put(
                        JSONObject().apply {
                            put("time", event.timestamp)
                            put("title", event.title)
                            put("type", event.type.name)
                            // Redact any possible sensitive info in export
                            put("detail", event.detail)
                        }
                    )
                }
                put("timeline", timelineArr)
            }
            jsonArray.put(sessionObj)
        }
        return jsonArray.toString(2)
    }

    private fun serializeTimelineJson(timeline: List<TimelineEvent>): String {
        val arr = JSONArray()
        timeline.forEach { event ->
            val obj = JSONObject().apply {
                put("id", event.id)
                put("ts", event.timestamp)
                put("title", event.title)
                put("detail", event.detail)
                put("type", event.type.name)
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseTimelineJson(json: String): List<TimelineEvent> {
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<TimelineEvent>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TimelineEvent(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        timestamp = obj.optLong("ts", System.currentTimeMillis()),
                        title = obj.optString("title", "Event"),
                        detail = obj.optString("detail", ""),
                        type = try {
                            TimelineEventType.valueOf(obj.optString("type", "SYSTEM"))
                        } catch (e: Exception) {
                            TimelineEventType.SYSTEM
                        }
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    internal fun serializeTranscriptsJson(hindi: List<TranscriptSegment>, english: List<TranscriptSegment>): String {
        val root = JSONObject()
        val hindiArr = JSONArray()
        hindi.forEach { seg ->
            hindiArr.put(JSONObject().apply {
                put("id", seg.id)
                put("ts", seg.timestamp)
                put("text", seg.text)
                put("lang", seg.language.code)
                if (seg.confidence != null) put("conf", seg.confidence)
                put("final", seg.isFinal)
            })
        }
        val englishArr = JSONArray()
        english.forEach { seg ->
            englishArr.put(JSONObject().apply {
                put("id", seg.id)
                put("ts", seg.timestamp)
                put("text", seg.text)
                put("lang", seg.language.code)
                if (seg.confidence != null) put("conf", seg.confidence)
                put("final", seg.isFinal)
            })
        }
        root.put("hindi", hindiArr)
        root.put("english", englishArr)
        return root.toString()
    }

    internal fun parseTranscriptsJson(json: String?): Pair<List<TranscriptSegment>, List<TranscriptSegment>> {
        if (json.isNullOrBlank()) return Pair(emptyList(), emptyList())
        val hindi = mutableListOf<TranscriptSegment>()
        val english = mutableListOf<TranscriptSegment>()
        try {
            if (json.trim().startsWith("{")) {
                val root = JSONObject(json)
                val hindiArr = root.optJSONArray("hindi")
                if (hindiArr != null) {
                    for (i in 0 until hindiArr.length()) {
                        val obj = hindiArr.getJSONObject(i)
                        hindi.add(
                            TranscriptSegment(
                                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                timestamp = obj.optLong("ts", System.currentTimeMillis()),
                                text = obj.optString("text", ""),
                                language = ExternalSpeechLanguage.fromCode(obj.optString("lang", "hi")),
                                confidence = if (obj.has("conf")) obj.optDouble("conf") else null,
                                isFinal = obj.optBoolean("final", true)
                            )
                        )
                    }
                }
                val englishArr = root.optJSONArray("english")
                if (englishArr != null) {
                    for (i in 0 until englishArr.length()) {
                        val obj = englishArr.getJSONObject(i)
                        english.add(
                            TranscriptSegment(
                                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                                timestamp = obj.optLong("ts", System.currentTimeMillis()),
                                text = obj.optString("text", ""),
                                language = ExternalSpeechLanguage.fromCode(obj.optString("lang", "en")),
                                confidence = if (obj.has("conf")) obj.optDouble("conf") else null,
                                isFinal = obj.optBoolean("final", true)
                            )
                        )
                    }
                }
            } else if (json.trim().startsWith("[")) {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val lang = ExternalSpeechLanguage.fromCode(obj.optString("lang", "en"))
                    val seg = TranscriptSegment(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        timestamp = obj.optLong("ts", System.currentTimeMillis()),
                        text = obj.optString("text", ""),
                        language = lang,
                        confidence = if (obj.has("conf")) obj.optDouble("conf") else null,
                        isFinal = obj.optBoolean("final", true)
                    )
                    if (lang == ExternalSpeechLanguage.HINDI) hindi.add(seg) else english.add(seg)
                }
            }
        } catch (e: Exception) {
            // Safe fallback
        }
        return Pair(hindi, english)
    }
}
