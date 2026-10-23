package com.example.sakhi.data.ai

import com.example.sakhi.core.model.DistressAssessment
import com.example.sakhi.core.model.DistressCategory
import com.example.sakhi.core.security.KeystoreSecretStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

interface AssessmentEngine {
    val providerName: String
    val isConfigured: Boolean
    suspend fun assessText(transcript: String): DistressAssessment
    suspend fun testConnection(): Result<String>
}

class LocalDeterministicAssessmentEngine(
    private val riskAnalyzer: EmergencyRiskAnalyzer = EmergencyRiskAnalyzer()
) : AssessmentEngine {

    override val providerName: String = "Local Heuristic Analyzer (Hindi/English)"
    override val isConfigured: Boolean = true

    override suspend fun assessText(transcript: String): DistressAssessment = withContext(Dispatchers.Default) {
        riskAnalyzer.analyzeSegment(transcript)
    }

    override suspend fun testConnection(): Result<String> {
        return Result.success("Local bilingual heuristic engine ready (Offline)")
    }
}

class GeminiAiAssessmentEngine(
    private val keystoreStorage: KeystoreSecretStorage,
    private val fallbackAnalyzer: EmergencyRiskAnalyzer = EmergencyRiskAnalyzer()
) : AssessmentEngine {

    companion object {
        const val KEY_API_KEY = "gemini_api_key"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val MAX_TRANSCRIPT_UPLOAD_CHARS = 300
    }

    override val providerName: String = "Google Gemini AI"

    override val isConfigured: Boolean
        get() {
            val key = keystoreStorage.getSecret(KEY_API_KEY)
            return !key.isNullOrBlank()
        }

    override suspend fun assessText(transcript: String): DistressAssessment = withContext(Dispatchers.IO) {
        val apiKey = keystoreStorage.getSecret(KEY_API_KEY)
        if (apiKey.isNullOrBlank()) {
            // Fall back to local heuristic when API key not configured
            val local = fallbackAnalyzer.analyzeSegment(transcript)
            return@withContext local.copy(providerStatus = "LOCAL_HEURISTIC (API Key Unset)")
        }

        // Bound transcript upload to recent window to respect user privacy and bandwidth
        val boundedSnippet = if (transcript.length > MAX_TRANSCRIPT_UPLOAD_CHARS) {
            transcript.takeLast(MAX_TRANSCRIPT_UPLOAD_CHARS)
        } else {
            transcript
        }

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent?key=$apiKey"
            val prompt = """
                Analyze the following spoken transcript from a woman's emergency wearable (supports English, Hindi, Hinglish).
                Determine if the speaker is in danger or distress.
                Output ONLY a JSON object with this exact structure:
                {
                   "category": "NO_CLEAR_INDICATOR" | "UNCERTAIN" | "ELEVATED_CONCERN" | "POSSIBLE_DISTRESS",
                   "confidence": 0.0 to 1.0,
                   "summary": "one concise sentence assessment"
                }
                Transcript: "$boundedSnippet"
            """.trimIndent()

            val body = JSONObject().apply {
                val contents = org.json.JSONArray().apply {
                    val partObj = JSONObject().apply {
                        val parts = org.json.JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        }
                        put("parts", parts)
                    }
                    put(partObj)
                }
                put("contents", contents)
            }

            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 7000

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val respText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                parseGeminiResponse(respText, boundedSnippet)
            } else {
                val local = fallbackAnalyzer.analyzeSegment(boundedSnippet)
                local.copy(
                    providerStatus = "LOCAL_FALLBACK (Cloud HTTP $responseCode)"
                )
            }
        } catch (e: Exception) {
            val local = fallbackAnalyzer.analyzeSegment(boundedSnippet)
            local.copy(
                providerStatus = "LOCAL_FALLBACK (Network Offline)"
            )
        }
    }

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = keystoreStorage.getSecret(KEY_API_KEY)
        if (apiKey.isNullOrBlank()) {
            return@withContext Result.failure(IllegalStateException("API key is not configured"))
        }

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent?key=$apiKey"
            val body = JSONObject().apply {
                val contents = org.json.JSONArray().apply {
                    val partObj = JSONObject().apply {
                        val parts = org.json.JSONArray().apply {
                            put(JSONObject().apply { put("text", "Ping test. Respond with OK.") })
                        }
                        put("parts", parts)
                    }
                    put(partObj)
                }
                put("contents", contents)
            }

            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 6000
                readTimeout = 6000
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code == 200) {
                Result.success("Connected to Gemini 2.5 Flash successfully")
            } else {
                Result.failure(Exception("Gemini returned HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseGeminiResponse(json: String, originalTranscript: String): DistressAssessment {
        return try {
            val root = JSONObject(json)
            val candidates = root.getJSONArray("candidates")
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val text = content.getJSONArray("parts").getJSONObject(0).getString("text")

            val cleanJson = text.replace("```json", "").replace("```", "").trim()
            val parsed = JSONObject(cleanJson)

            val catStr = parsed.optString("category", "NO_CLEAR_INDICATOR")
            val cat = when (catStr.uppercase()) {
                "POSSIBLE_DISTRESS" -> DistressCategory.POSSIBLE_DISTRESS
                "ELEVATED_CONCERN" -> DistressCategory.ELEVATED_CONCERN
                "UNCERTAIN" -> DistressCategory.UNCERTAIN
                "NORMAL" -> DistressCategory.NORMAL
                else -> DistressCategory.NO_CLEAR_INDICATOR
            }
            val conf = parsed.optDouble("confidence", 0.8).toFloat()
            val sum = parsed.optString("summary", "Evaluated by Gemini Cloud AI")

            DistressAssessment(
                category = cat,
                confidence = conf,
                transcript = originalTranscript,
                contextSummary = "Cloud AI Assessment: $sum",
                providerStatus = "GEMINI_AI (Cloud)",
                triggerPhraseMatch = cat == DistressCategory.POSSIBLE_DISTRESS || cat == DistressCategory.ELEVATED_CONCERN
            )
        } catch (e: Exception) {
            val local = fallbackAnalyzer.analyzeSegment(originalTranscript)
            local.copy(
                providerStatus = "LOCAL_FALLBACK (Cloud Parse Error)"
            )
        }
    }
}
