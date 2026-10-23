package com.example.sakhi.data.assistant

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Supported speech recognition languages for external Bluetooth (ESP32) audio.
 */
enum class ExternalSpeechLanguage(val code: String, val displayName: String, val targetSampleRate: Float) {
    ENGLISH("en", "English (8 kHz)", 8000.0f),
    HINDI("hi", "Hindi (16 kHz)", 16000.0f);

    companion object {
        fun fromCode(code: String): ExternalSpeechLanguage {
            return entries.find { it.code.equals(code, ignoreCase = true) } ?: ENGLISH
        }
    }
}

/**
 * Lifecycle states of an offline acoustic speech model.
 */
enum class VoskModelState {
    NOT_INITIALIZED,
    DOWNLOADING,
    UNPACKING,
    READY,
    ERROR
}

/**
 * Manages the offline Vosk speech recognition acoustic model lifecycle.
 * - English model: Bundled in app assets (Indian English / 8 kHz native), unpacked on first run.
 * - Hindi model: On-demand downloadable model (vosk-model-small-hi-0.22 / 16 kHz), unpacked and verified.
 * Guarantees zero network calls during emergency sessions once models are cached in app-private storage.
 */
class VoskModelManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "VoskModelManager"

        const val ENGLISH_MODEL_DIR_NAME = "vosk-model-en"
        const val HINDI_MODEL_DIR_NAME = "vosk-model-hi"
        const val LEGACY_MODEL_DIR_NAME = "vosk-model"
        const val ASSET_SOURCE_DIR = "model"
        const val ASSET_HINDI_SOURCE_DIR = "model-hi"

        const val HINDI_MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip"
        const val HINDI_ZIP_NAME = "vosk-model-small-hi-0.22.zip"

        // Required free storage space in bytes (~150 MB for zip + uncompressed model)
        const val MIN_STORAGE_REQUIRED_BYTES = 150 * 1024 * 1024L
    }

    private val _currentLanguage = MutableStateFlow(ExternalSpeechLanguage.ENGLISH)
    val currentLanguage: StateFlow<ExternalSpeechLanguage> = _currentLanguage.asStateFlow()

    // Per-Language States
    private val _englishModelState = MutableStateFlow(VoskModelState.NOT_INITIALIZED)
    val englishModelState: StateFlow<VoskModelState> = _englishModelState.asStateFlow()

    private val _englishStatusMessage = MutableStateFlow("English model not initialized")
    val englishStatusMessage: StateFlow<String> = _englishStatusMessage.asStateFlow()

    private val _hindiModelState = MutableStateFlow(VoskModelState.NOT_INITIALIZED)
    val hindiModelState: StateFlow<VoskModelState> = _hindiModelState.asStateFlow()

    private val _hindiStatusMessage = MutableStateFlow("Hindi model not installed")
    val hindiStatusMessage: StateFlow<String> = _hindiStatusMessage.asStateFlow()

    private val _hindiDownloadProgress = MutableStateFlow(0)
    val hindiDownloadProgress: StateFlow<Int> = _hindiDownloadProgress.asStateFlow()

    // Unified / Backwards-Compatible States (Tracking Current Selected Language)
    private val _modelState = MutableStateFlow(VoskModelState.NOT_INITIALIZED)
    val modelState: StateFlow<VoskModelState> = _modelState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Offline speech model not initialized")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    val targetEnglishModelDir: File
        get() = File(context.filesDir, ENGLISH_MODEL_DIR_NAME)

    val targetHindiModelDir: File
        get() = File(context.filesDir, HINDI_MODEL_DIR_NAME)

    val legacyModelDir: File
        get() = File(context.filesDir, LEGACY_MODEL_DIR_NAME)

    val targetModelDir: File
        get() = getModelDir(_currentLanguage.value)

    private var hindiDownloadJob: Job? = null

    fun getModelDir(language: ExternalSpeechLanguage): File {
        return when (language) {
            ExternalSpeechLanguage.ENGLISH -> {
                // If legacy dir exists and has valid model, prioritize it or point to target
                if (isModelValid(targetEnglishModelDir)) {
                    targetEnglishModelDir
                } else if (isModelValid(legacyModelDir)) {
                    legacyModelDir
                } else {
                    targetEnglishModelDir
                }
            }
            ExternalSpeechLanguage.HINDI -> targetHindiModelDir
        }
    }

    fun initialize() {
        scope.launch(Dispatchers.IO) {
            // Check status of both models on launch
            refreshModelStates()

            // Automatically prepare the bundled English model if needed
            if (!isLanguageReady(ExternalSpeechLanguage.ENGLISH)) {
                prepareEnglishModel()
            }

            // Automatically prepare the bundled Hindi model if needed
            if (!isLanguageReady(ExternalSpeechLanguage.HINDI)) {
                prepareHindiModel()
            }

            syncActiveState()
        }
    }

    fun setLanguage(language: ExternalSpeechLanguage) {
        _currentLanguage.value = language
        syncActiveState()
    }

    private fun syncActiveState() {
        when (_currentLanguage.value) {
            ExternalSpeechLanguage.ENGLISH -> {
                _modelState.value = _englishModelState.value
                _statusMessage.value = _englishStatusMessage.value
            }
            ExternalSpeechLanguage.HINDI -> {
                _modelState.value = _hindiModelState.value
                _statusMessage.value = _hindiStatusMessage.value
            }
        }
    }

    fun isLanguageReady(language: ExternalSpeechLanguage): Boolean {
        val dir = getModelDir(language)
        return isModelValid(dir)
    }

    fun refreshModelStates() {
        val englishDir = getModelDir(ExternalSpeechLanguage.ENGLISH)
        if (isModelValid(englishDir)) {
            _englishModelState.value = VoskModelState.READY
            _englishStatusMessage.value = "Offline model ready (Indian English / 8 kHz)"
        } else {
            _englishModelState.value = VoskModelState.NOT_INITIALIZED
            _englishStatusMessage.value = "English model not extracted"
        }

        val hindiDir = targetHindiModelDir
        if (isModelValid(hindiDir)) {
            _hindiModelState.value = VoskModelState.READY
            _hindiStatusMessage.value = "Offline model ready (Hindi / 16 kHz)"
            _hindiDownloadProgress.value = 100
        } else if (_hindiModelState.value != VoskModelState.DOWNLOADING && _hindiModelState.value != VoskModelState.UNPACKING) {
            _hindiModelState.value = VoskModelState.NOT_INITIALIZED
            _hindiStatusMessage.value = "Hindi model not extracted"
            _hindiDownloadProgress.value = 0
        }
        syncActiveState()
    }

    suspend fun ensureModelReady(language: ExternalSpeechLanguage = _currentLanguage.value): String? = withContext(Dispatchers.IO) {
        when (language) {
            ExternalSpeechLanguage.ENGLISH -> {
                val dir = getModelDir(ExternalSpeechLanguage.ENGLISH)
                if (isModelValid(dir)) {
                    _englishModelState.value = VoskModelState.READY
                    _englishStatusMessage.value = "Offline model ready (Indian English / 8 kHz)"
                    syncActiveState()
                    return@withContext resolveCanonicalModelDir(dir).absolutePath
                }
                prepareEnglishModel()
                if (isLanguageReady(ExternalSpeechLanguage.ENGLISH)) {
                    val resolved = resolveCanonicalModelDir(getModelDir(ExternalSpeechLanguage.ENGLISH))
                    syncActiveState()
                    resolved.absolutePath
                } else {
                    syncActiveState()
                    null
                }
            }
            ExternalSpeechLanguage.HINDI -> {
                val dir = targetHindiModelDir
                if (isModelValid(dir)) {
                    _hindiModelState.value = VoskModelState.READY
                    _hindiStatusMessage.value = "Offline model ready (Hindi / 16 kHz)"
                    syncActiveState()
                    return@withContext resolveCanonicalModelDir(dir).absolutePath
                }
                prepareHindiModel()
                if (isLanguageReady(ExternalSpeechLanguage.HINDI)) {
                    val resolved = resolveCanonicalModelDir(targetHindiModelDir)
                    syncActiveState()
                    resolved.absolutePath
                } else {
                    syncActiveState()
                    null
                }
            }
        }
    }

    /**
     * Resolves the actual directory containing Kaldi model files (handles nested zip folder if present).
     */
    fun resolveCanonicalModelDir(dir: File): File {
        if (!dir.exists() || !dir.isDirectory) return dir
        if (File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()) return dir
        val subdirs = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (sub in subdirs) {
            if (File(sub, "am/final.mdl").exists() || File(sub, "final.mdl").exists()) {
                return sub
            }
        }
        return dir
    }

    fun isModelValid(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val resolved = resolveCanonicalModelDir(dir)
        val mdl = File(resolved, "am/final.mdl")
        val altMdl = File(resolved, "final.mdl")
        val conf = File(resolved, "conf/mfcc.conf")
        return (mdl.exists() && mdl.length() > 50_000L || altMdl.exists() && altMdl.length() > 50_000L) && conf.exists()
    }

    /**
     * Prepares the bundled English model from APK assets.
     */
    @Synchronized
    private fun prepareEnglishModel() {
        try {
            val targetDir = targetEnglishModelDir
            if (isModelValid(targetDir)) {
                _englishModelState.value = VoskModelState.READY
                _englishStatusMessage.value = "Offline model ready (Indian English / 8 kHz)"
                syncActiveState()
                return
            }
            if (isModelValid(legacyModelDir)) {
                _englishModelState.value = VoskModelState.READY
                _englishStatusMessage.value = "Offline model ready (Indian English / 8 kHz)"
                syncActiveState()
                return
            }

            _englishModelState.value = VoskModelState.UNPACKING
            _englishStatusMessage.value = "Unpacking English model from app assets..."
            syncActiveState()

            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            copyAssetFolder(ASSET_SOURCE_DIR, targetDir)

            if (isModelValid(targetDir)) {
                _englishModelState.value = VoskModelState.READY
                _englishStatusMessage.value = "Offline model ready (Indian English / 8 kHz)"
                Log.i(TAG, "English model successfully extracted and verified at: ${targetDir.absolutePath}")
            } else {
                _englishModelState.value = VoskModelState.ERROR
                _englishStatusMessage.value = "English model validation failed (missing final.mdl or mfcc.conf)"
                Log.e(TAG, "Unpacked English model directory invalid.")
            }
        } catch (e: Exception) {
            _englishModelState.value = VoskModelState.ERROR
            _englishStatusMessage.value = "Failed to unpack English model: ${e.message}"
            Log.e(TAG, "Error unpacking English model", e)
        } finally {
            syncActiveState()
        }
    }

    /**
     * Prepares the bundled Hindi model from APK assets.
     * Does not re-extract if a valid installed model already exists.
     */
    @Synchronized
    fun prepareHindiModel() {
        try {
            val targetDir = targetHindiModelDir
            if (isModelValid(targetDir)) {
                _hindiModelState.value = VoskModelState.READY
                _hindiStatusMessage.value = "Offline model ready (Hindi / 16 kHz)"
                _hindiDownloadProgress.value = 100
                syncActiveState()
                return
            }

            val assetManager = context.assets
            val bundledHindiExists = try {
                val list = assetManager.list(ASSET_HINDI_SOURCE_DIR)
                list != null && list.isNotEmpty()
            } catch (_: Exception) {
                false
            }

            if (!bundledHindiExists) {
                Log.w(TAG, "Bundled Hindi model assets not found in '$ASSET_HINDI_SOURCE_DIR'. Operating in downloadable mode.")
                return
            }

            _hindiModelState.value = VoskModelState.UNPACKING
            _hindiStatusMessage.value = "Unpacking Hindi model from app assets..."
            syncActiveState()

            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()

            copyAssetFolder(ASSET_HINDI_SOURCE_DIR, targetDir)

            if (isModelValid(targetDir)) {
                _hindiModelState.value = VoskModelState.READY
                _hindiStatusMessage.value = "Offline model ready (Hindi / 16 kHz)"
                _hindiDownloadProgress.value = 100
                Log.i(TAG, "Hindi model successfully extracted and verified at: ${targetDir.absolutePath}")
            } else {
                _hindiModelState.value = VoskModelState.ERROR
                _hindiStatusMessage.value = "Hindi model validation failed (missing final.mdl or mfcc.conf)"
                Log.e(TAG, "Unpacked Hindi model directory invalid.")
            }
        } catch (e: Exception) {
            _hindiModelState.value = VoskModelState.ERROR
            _hindiStatusMessage.value = "Failed to unpack Hindi model: ${e.message}"
            Log.e(TAG, "Error unpacking Hindi model", e)
        } finally {
            syncActiveState()
        }
    }

    /**
     * Initiates asynchronous on-demand download of the Hindi model archive.
     * Validates storage space, handles interruptions, and extracts safely.
     */
    fun downloadHindiModel(
        customUrl: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (_hindiModelState.value == VoskModelState.DOWNLOADING) {
            Log.w(TAG, "Hindi model download already in progress.")
            return
        }

        hindiDownloadJob?.cancel()
        hindiDownloadJob = scope.launch(Dispatchers.IO) {
            val downloadUrl = customUrl ?: HINDI_MODEL_URL
            val downloadTempFile = File(context.filesDir, "$HINDI_ZIP_NAME.download")
            val targetZipFile = File(context.filesDir, HINDI_ZIP_NAME)
            val tempExtractDir = File(context.filesDir, "vosk-model-hi-temp")

            try {
                // 1. Verify available device storage
                val usableSpace = context.filesDir.usableSpace
                if (usableSpace < MIN_STORAGE_REQUIRED_BYTES) {
                    val msg = "Insufficient storage: ~150 MB required (available: ${usableSpace / (1024 * 1024)} MB)"
                    _hindiModelState.value = VoskModelState.ERROR
                    _hindiStatusMessage.value = msg
                    syncActiveState()
                    onComplete?.invoke(false)
                    return@launch
                }

                _hindiModelState.value = VoskModelState.DOWNLOADING
                _hindiStatusMessage.value = "Connecting to model repository..."
                _hindiDownloadProgress.value = 0
                syncActiveState()

                if (downloadTempFile.exists()) downloadTempFile.delete()

                // 2. Perform HTTP download with streaming and progress
                val url = URL(downloadUrl)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "SAKHI-Safety-App/1.0")
                }

                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw IOException("HTTP error $responseCode: ${connection.responseMessage}")
                }

                val totalBytes = connection.contentLengthLong
                var bytesDownloaded = 0L

                BufferedInputStream(connection.inputStream, 65536).use { input ->
                    FileOutputStream(downloadTempFile).use { output ->
                        val buffer = ByteArray(32768)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!isActive) {
                                throw IOException("Download cancelled by user or lifecycle.")
                            }
                            output.write(buffer, 0, read)
                            bytesDownloaded += read
                            if (totalBytes > 0) {
                                val progress = ((bytesDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                                _hindiDownloadProgress.value = progress
                                _hindiStatusMessage.value = "Downloading Hindi model: $progress% (${bytesDownloaded / (1024 * 1024)} MB)"
                                syncActiveState()
                            }
                        }
                        output.flush()
                    }
                }

                // 3. Atomically rename to final zip file
                if (targetZipFile.exists()) targetZipFile.delete()
                if (!downloadTempFile.renameTo(targetZipFile)) {
                    throw IOException("Failed to rename temporary download archive.")
                }

                // 4. Extract archive
                _hindiModelState.value = VoskModelState.UNPACKING
                _hindiStatusMessage.value = "Verifying and extracting Hindi model..."
                syncActiveState()

                if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()
                tempExtractDir.mkdirs()

                unzipArchiveSafely(targetZipFile, tempExtractDir)

                // 5. Verify extracted model integrity
                if (!isModelValid(tempExtractDir)) {
                    throw IOException("Extracted Hindi model invalid: missing final.mdl or mfcc.conf.")
                }

                // 6. Atomically replace target directory
                if (targetHindiModelDir.exists()) targetHindiModelDir.deleteRecursively()
                val resolvedSub = resolveCanonicalModelDir(tempExtractDir)
                if (resolvedSub != tempExtractDir) {
                    resolvedSub.renameTo(targetHindiModelDir)
                    tempExtractDir.deleteRecursively()
                } else {
                    tempExtractDir.renameTo(targetHindiModelDir)
                }

                // Cleanup zip archive to save device disk space
                if (targetZipFile.exists()) targetZipFile.delete()

                _hindiModelState.value = VoskModelState.READY
                _hindiStatusMessage.value = "Offline model ready (Hindi / 16 kHz)"
                _hindiDownloadProgress.value = 100
                syncActiveState()
                Log.i(TAG, "Hindi model successfully downloaded and verified at: ${targetHindiModelDir.absolutePath}")
                onComplete?.invoke(true)

            } catch (e: Exception) {
                if (downloadTempFile.exists()) downloadTempFile.delete()
                if (tempExtractDir.exists()) tempExtractDir.deleteRecursively()

                _hindiModelState.value = VoskModelState.ERROR
                _hindiStatusMessage.value = "Hindi model error: ${e.message}"
                syncActiveState()
                Log.e(TAG, "Error downloading Hindi Vosk model", e)
                onComplete?.invoke(false)
            }
        }
    }

    fun cancelHindiDownload() {
        hindiDownloadJob?.cancel()
        hindiDownloadJob = null
        val temp = File(context.filesDir, "$HINDI_ZIP_NAME.download")
        if (temp.exists()) temp.delete()
        refreshModelStates()
    }

    /**
     * Safely unzips an archive, protecting against Zip Slip path traversal vulnerabilities.
     */
    fun unzipArchiveSafely(zipFile: File, targetDir: File) {
        val canonicalDestDir = targetDir.canonicalPath
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry?
            val buffer = ByteArray(32768)
            while (zis.nextEntry.also { entry = it } != null) {
                val current = entry!!
                val destFile = File(targetDir, current.name)
                val canonicalDestFile = destFile.canonicalPath

                // Path traversal check
                if (!canonicalDestFile.startsWith(canonicalDestDir + File.separator) &&
                    canonicalDestFile != canonicalDestDir
                ) {
                    throw SecurityException("Zip entry path traversal detected: ${current.name}")
                }

                if (current.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    /**
     * Allows manually importing a pre-downloaded model zip file for testing or air-gapped setups.
     */
    fun importModelArchive(zipFile: File, language: ExternalSpeechLanguage): Boolean {
        if (!zipFile.exists() || !zipFile.isFile) return false
        val destDir = when (language) {
            ExternalSpeechLanguage.ENGLISH -> targetEnglishModelDir
            ExternalSpeechLanguage.HINDI -> targetHindiModelDir
        }
        val tempDir = File(context.filesDir, "import-temp-${language.code}")
        return try {
            if (tempDir.exists()) tempDir.deleteRecursively()
            tempDir.mkdirs()

            unzipArchiveSafely(zipFile, tempDir)

            if (!isModelValid(tempDir)) {
                tempDir.deleteRecursively()
                return false
            }

            if (destDir.exists()) destDir.deleteRecursively()
            val resolved = resolveCanonicalModelDir(tempDir)
            if (resolved != tempDir) {
                resolved.renameTo(destDir)
                tempDir.deleteRecursively()
            } else {
                tempDir.renameTo(destDir)
            }

            refreshModelStates()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import model archive: ${e.message}", e)
            if (tempDir.exists()) tempDir.deleteRecursively()
            false
        }
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        val assetManager = context.assets
        val list = assetManager.list(assetPath) ?: return

        if (list.isEmpty()) {
            copyAssetFile(assetPath, targetDir)
        } else {
            targetDir.mkdirs()
            for (file in list) {
                val subAssetPath = if (assetPath.isEmpty()) file else "$assetPath/$file"
                val subTarget = File(targetDir, file)
                val subList = assetManager.list(subAssetPath)
                if (subList != null && subList.isNotEmpty()) {
                    copyAssetFolder(subAssetPath, subTarget)
                } else {
                    copyAssetFile(subAssetPath, subTarget)
                }
            }
        }
    }

    private fun copyAssetFile(assetFilePath: String, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        context.assets.open(assetFilePath).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output, bufferSize = 65536)
            }
        }
    }

    fun getModelPath(language: ExternalSpeechLanguage = _currentLanguage.value): String? {
        val dir = getModelDir(language)
        return if (isModelValid(dir)) resolveCanonicalModelDir(dir).absolutePath else null
    }
}
