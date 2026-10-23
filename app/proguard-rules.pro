# Proguard & R8 rules for SAKHI Production Hardening

# --- Vosk Offline Speech Recognition ---
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# --- Java Native Access (JNA) ---
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# --- SAKHI Native Audio Processing (Speex DSP) ---
-keep class com.example.sakhi.data.audio.LibSpeexDsp { *; }
-keepclassmembers class com.example.sakhi.data.audio.** {
    native <methods>;
}

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Coroutines ---
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# --- Android Architecture & Data Models ---
-keep class com.example.sakhi.core.model.** { *; }
-keep class com.example.sakhi.data.tracking.LiveTrackingSession { *; }
-keep class com.example.sakhi.data.location.SakhiLocation { *; }
