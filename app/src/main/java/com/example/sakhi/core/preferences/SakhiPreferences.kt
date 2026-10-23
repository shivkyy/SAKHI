package com.example.sakhi.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sakhi_settings")

open class SakhiPreferences(private val context: Context? = null) {

    companion object {
        val KEY_COUNTDOWN_SECONDS = intPreferencesKey("countdown_seconds")
        val KEY_MESSAGE_TEMPLATE = stringPreferencesKey("message_template")
        val KEY_INCLUDE_LOCATION_ACCURACY = booleanPreferencesKey("include_location_accuracy")
        val KEY_AI_ESCALATION_ENABLED = booleanPreferencesKey("ai_escalation_enabled")
        val KEY_ESCALATION_SENSITIVITY = stringPreferencesKey("escalation_sensitivity")
        val KEY_POLICE_NUMBER = stringPreferencesKey("police_number")
        val KEY_SHOW_TRANSCRIPTS = booleanPreferencesKey("show_transcripts")
        val KEY_LANGUAGE = stringPreferencesKey("app_language")
        val KEY_THEME = stringPreferencesKey("app_theme")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_LIVE_TRACKING_ENABLED = booleanPreferencesKey("live_tracking_enabled")
        val KEY_LIVE_TRACKING_URL = stringPreferencesKey("live_tracking_url")
        val KEY_PERIODIC_SMS_ENABLED = booleanPreferencesKey("periodic_sms_enabled")
        val KEY_PERIODIC_SMS_INTERVAL = intPreferencesKey("periodic_sms_interval")

        // Emergency Call Escalation
        val KEY_AUTO_CALL_ESCALATION_ENABLED = booleanPreferencesKey("auto_call_escalation_enabled")
        val KEY_ESCALATION_PHONE_NUMBER = stringPreferencesKey("escalation_phone_number")
        val KEY_ESCALATION_POLICY = stringPreferencesKey("escalation_policy")

        // Emergency Speech Analysis & Privacy
        val KEY_EMERGENCY_SPEECH_MONITORING = booleanPreferencesKey("emergency_speech_monitoring")
        val KEY_INCLUDE_SPEECH_IN_SMS = booleanPreferencesKey("include_speech_in_sms")

        // External Bluetooth Audio Speech Language
        val KEY_EXTERNAL_AUDIO_LANGUAGE = stringPreferencesKey("external_audio_language")
        const val DEFAULT_EXTERNAL_AUDIO_LANGUAGE = "en"

        const val DEFAULT_COUNTDOWN = 5
        const val DEFAULT_POLICE_NUMBER = "112"
        const val DEFAULT_SENSITIVITY = "BALANCED"
        const val DEFAULT_LIVE_TRACKING_URL = "http://10.0.2.2:3000"
        const val DEFAULT_PERIODIC_SMS_INTERVAL = 60
        const val DEFAULT_ESCALATION_POLICY = "CALL_ON_RISK_ESCALATION"
        const val DEFAULT_MESSAGE_TEMPLATE =
            "SAKHI EMERGENCY ALERT\nAn emergency session has been activated.\nTime: {time}\nLocation: {location}\nAccuracy: {accuracy}\nPlease arrange immediate assistance."
    }

    open val countdownSecondsFlow: Flow<Int>
        get() = context?.dataStore?.data?.map { it[KEY_COUNTDOWN_SECONDS] ?: DEFAULT_COUNTDOWN } ?: flowOf(DEFAULT_COUNTDOWN)

    open val messageTemplateFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_MESSAGE_TEMPLATE] ?: DEFAULT_MESSAGE_TEMPLATE } ?: flowOf(DEFAULT_MESSAGE_TEMPLATE)

    open val includeLocationAccuracyFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_INCLUDE_LOCATION_ACCURACY] ?: true } ?: flowOf(true)

    open val aiEscalationEnabledFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_AI_ESCALATION_ENABLED] ?: true } ?: flowOf(true)

    open val escalationSensitivityFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_ESCALATION_SENSITIVITY] ?: DEFAULT_SENSITIVITY } ?: flowOf(DEFAULT_SENSITIVITY)

    open val policeNumberFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_POLICE_NUMBER] ?: DEFAULT_POLICE_NUMBER } ?: flowOf(DEFAULT_POLICE_NUMBER)

    open val showTranscriptsFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_SHOW_TRANSCRIPTS] ?: false } ?: flowOf(false)

    open val languageFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_LANGUAGE] ?: "en" } ?: flowOf("en")

    open val themeFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_THEME] ?: "SYSTEM" } ?: flowOf("SYSTEM")

    open val onboardingCompletedFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_ONBOARDING_COMPLETED] ?: false } ?: flowOf(false)

    open val liveTrackingEnabledFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_LIVE_TRACKING_ENABLED] ?: true } ?: flowOf(true)

    open val liveTrackingUrlFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_LIVE_TRACKING_URL] ?: DEFAULT_LIVE_TRACKING_URL } ?: flowOf(DEFAULT_LIVE_TRACKING_URL)

    open val periodicSmsEnabledFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_PERIODIC_SMS_ENABLED] ?: true } ?: flowOf(true)

    open val periodicSmsIntervalFlow: Flow<Int>
        get() = context?.dataStore?.data?.map { it[KEY_PERIODIC_SMS_INTERVAL] ?: DEFAULT_PERIODIC_SMS_INTERVAL } ?: flowOf(DEFAULT_PERIODIC_SMS_INTERVAL)

    open val autoCallEscalationEnabledFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_AUTO_CALL_ESCALATION_ENABLED] ?: false } ?: flowOf(false)

    open val escalationPhoneNumberFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_ESCALATION_PHONE_NUMBER] ?: "" } ?: flowOf("")

    open val escalationPolicyFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_ESCALATION_POLICY] ?: DEFAULT_ESCALATION_POLICY } ?: flowOf(DEFAULT_ESCALATION_POLICY)

    open val emergencySpeechMonitoringFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_EMERGENCY_SPEECH_MONITORING] ?: true } ?: flowOf(true)

    open val includeSpeechInSmsFlow: Flow<Boolean>
        get() = context?.dataStore?.data?.map { it[KEY_INCLUDE_SPEECH_IN_SMS] ?: false } ?: flowOf(false)

    open suspend fun setCountdownSeconds(seconds: Int) {
        context?.dataStore?.edit { it[KEY_COUNTDOWN_SECONDS] = seconds }
    }

    open suspend fun setMessageTemplate(template: String) {
        context?.dataStore?.edit { it[KEY_MESSAGE_TEMPLATE] = template }
    }

    open suspend fun setIncludeLocationAccuracy(include: Boolean) {
        context?.dataStore?.edit { it[KEY_INCLUDE_LOCATION_ACCURACY] = include }
    }

    open suspend fun setAiEscalationEnabled(enabled: Boolean) {
        context?.dataStore?.edit { it[KEY_AI_ESCALATION_ENABLED] = enabled }
    }

    open suspend fun setEscalationSensitivity(sensitivity: String) {
        context?.dataStore?.edit { it[KEY_ESCALATION_SENSITIVITY] = sensitivity }
    }

    open suspend fun setPoliceNumber(number: String) {
        context?.dataStore?.edit { it[KEY_POLICE_NUMBER] = number }
    }

    open suspend fun setShowTranscripts(show: Boolean) {
        context?.dataStore?.edit { it[KEY_SHOW_TRANSCRIPTS] = show }
    }

    open suspend fun setLanguage(lang: String) {
        context?.dataStore?.edit { it[KEY_LANGUAGE] = lang }
    }

    open suspend fun setTheme(theme: String) {
        context?.dataStore?.edit { it[KEY_THEME] = theme }
    }

    open suspend fun setOnboardingCompleted(completed: Boolean) {
        context?.dataStore?.edit { it[KEY_ONBOARDING_COMPLETED] = completed }
    }

    open suspend fun setLiveTrackingEnabled(enabled: Boolean) {
        context?.dataStore?.edit { it[KEY_LIVE_TRACKING_ENABLED] = enabled }
    }

    open suspend fun setLiveTrackingUrl(url: String) {
        context?.dataStore?.edit { it[KEY_LIVE_TRACKING_URL] = url }
    }

    open suspend fun setPeriodicSmsEnabled(enabled: Boolean) {
        context?.dataStore?.edit { it[KEY_PERIODIC_SMS_ENABLED] = enabled }
    }

    open suspend fun setPeriodicSmsInterval(interval: Int) {
        context?.dataStore?.edit { it[KEY_PERIODIC_SMS_INTERVAL] = interval }
    }

    open suspend fun setAutoCallEscalationEnabled(enabled: Boolean) {
        context?.dataStore?.edit { it[KEY_AUTO_CALL_ESCALATION_ENABLED] = enabled }
    }

    open suspend fun setEscalationPhoneNumber(number: String) {
        context?.dataStore?.edit { it[KEY_ESCALATION_PHONE_NUMBER] = normalizePhoneNumber(number) }
    }

    open suspend fun setEscalationPolicy(policy: String) {
        context?.dataStore?.edit { it[KEY_ESCALATION_POLICY] = policy }
    }

    open suspend fun setEmergencySpeechMonitoring(enabled: Boolean) {
        context?.dataStore?.edit { it[KEY_EMERGENCY_SPEECH_MONITORING] = enabled }
    }

    open suspend fun setIncludeSpeechInSms(include: Boolean) {
        context?.dataStore?.edit { it[KEY_INCLUDE_SPEECH_IN_SMS] = include }
    }

    open val externalAudioLanguageFlow: Flow<String>
        get() = context?.dataStore?.data?.map { it[KEY_EXTERNAL_AUDIO_LANGUAGE] ?: DEFAULT_EXTERNAL_AUDIO_LANGUAGE } ?: flowOf(DEFAULT_EXTERNAL_AUDIO_LANGUAGE)

    open suspend fun setExternalAudioLanguage(language: String) {
        context?.dataStore?.edit { it[KEY_EXTERNAL_AUDIO_LANGUAGE] = language }
    }

    fun normalizePhoneNumber(input: String): String {
        return input.trim().replace(Regex("[^0-9+]"), "")
    }
}
