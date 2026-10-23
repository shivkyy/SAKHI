package com.example.sakhi.data.tracking

import com.example.sakhi.data.location.SakhiLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class LiveTrackingSession(
    val sessionId: String,
    val trackingToken: String,
    val writerKey: String,
    val trackingUrl: String,
    val expiresAt: Long
)

interface LiveTrackingClient {
    suspend fun createSession(baseUrl: String, initialLocation: SakhiLocation?): Result<LiveTrackingSession>
    suspend fun uploadLocation(baseUrl: String, sessionId: String, writerKey: String, location: SakhiLocation): Result<Boolean>
    suspend fun cancelSession(baseUrl: String, sessionId: String, writerKey: String, reason: String = "USER_DISARMED"): Result<Boolean>
    suspend fun testConnection(baseUrl: String): Result<Boolean>
}

class HttpLiveTrackingClient : LiveTrackingClient {

    private fun normalizeBaseUrl(url: String): String {
        var trimmed = url.trim().trimEnd('/')
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }
        return trimmed
    }

    override suspend fun createSession(
        baseUrl: String,
        initialLocation: SakhiLocation?
    ): Result<LiveTrackingSession> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val root = normalizeBaseUrl(baseUrl)
            val url = URL("$root/api/sessions")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            val payload = JSONObject()
            if (initialLocation != null) {
                val locObj = JSONObject().apply {
                    put("latitude", initialLocation.latitude)
                    put("longitude", initialLocation.longitude)
                    if (initialLocation.accuracy != null) {
                        put("accuracy", initialLocation.accuracy.toDouble())
                    }
                    put("timestamp", initialLocation.timestamp)
                }
                payload.put("initialLocation", locObj)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val responseText = readResponseStream(connection)
                val json = JSONObject(responseText)
                val session = LiveTrackingSession(
                    sessionId = json.getString("sessionId"),
                    trackingToken = json.getString("trackingToken"),
                    writerKey = json.getString("writerKey"),
                    trackingUrl = json.getString("trackingUrl"),
                    expiresAt = json.optLong("expiresAt", System.currentTimeMillis() + 7200000L)
                )
                Result.success(session)
            } else {
                val errText = readErrorStream(connection)
                Result.failure(Exception("HTTP $statusCode: $errText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun uploadLocation(
        baseUrl: String,
        sessionId: String,
        writerKey: String,
        location: SakhiLocation
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val root = normalizeBaseUrl(baseUrl)
            val url = URL("$root/api/sessions/$sessionId/location")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Writer-Key", writerKey)
            }

            val payload = JSONObject().apply {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                if (location.accuracy != null) {
                    put("accuracy", location.accuracy.toDouble())
                }
                put("timestamp", location.timestamp)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                Result.success(true)
            } else {
                val errText = readErrorStream(connection)
                Result.failure(Exception("HTTP $statusCode: $errText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun cancelSession(
        baseUrl: String,
        sessionId: String,
        writerKey: String,
        reason: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val root = normalizeBaseUrl(baseUrl)
            val url = URL("$root/api/sessions/$sessionId/cancel")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Writer-Key", writerKey)
            }

            val payload = JSONObject().apply {
                put("reason", reason)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                Result.success(true)
            } else {
                val errText = readErrorStream(connection)
                Result.failure(Exception("HTTP $statusCode: $errText"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    override suspend fun testConnection(baseUrl: String): Result<Boolean> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val root = normalizeBaseUrl(baseUrl)
            val url = URL("$root/health")
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                doInput = true
                setRequestProperty("Accept", "application/json")
            }

            val statusCode = connection.responseCode
            if (statusCode in 200..299) {
                val body = readResponseStream(connection)
                val json = JSONObject(body)
                val ok = json.optString("status") == "ok"
                Result.success(ok)
            } else {
                Result.failure(Exception("Server returned HTTP $statusCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readResponseStream(connection: HttpURLConnection): String {
        return BufferedReader(InputStreamReader(connection.inputStream, "UTF-8")).use { reader ->
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        }
    }

    private fun readErrorStream(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: return ""
        return BufferedReader(InputStreamReader(stream, "UTF-8")).use { reader ->
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            sb.toString()
        }
    }
}
