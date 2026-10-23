package com.example.sakhi.data.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.sakhi.core.model.AiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

interface AudioPipeline {
    val aiState: StateFlow<AiState>
    val audioLevelRms: StateFlow<Float>
    val isCapturing: Boolean

    fun startCapture(scope: CoroutineScope)
    fun stopCapture()
    fun getTransientPcmData(): ByteArray?
}

class AndroidAudioPipeline(private val context: Context) : AudioPipeline {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val MAX_TRANSIENT_BUFFER_SECONDS = 6
    }

    private val _aiState = MutableStateFlow(AiState.OFF)
    override val aiState: StateFlow<AiState> = _aiState.asStateFlow()

    private val _audioLevelRms = MutableStateFlow(0f)
    override val audioLevelRms: StateFlow<Float> = _audioLevelRms.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    override var isCapturing: Boolean = false
        private set

    // Transient in-memory ring buffer (never persisted to disk, respecting privacy)
    private val bufferSize = SAMPLE_RATE * 2 * MAX_TRANSIENT_BUFFER_SECONDS
    private val transientBuffer = ByteArray(bufferSize)
    private var bufferWriteIndex = 0

    @SuppressLint("MissingPermission")
    override fun startCapture(scope: CoroutineScope) {
        if (isCapturing) return

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufSize <= 0) {
            _aiState.value = AiState.ERROR
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                minBufSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _aiState.value = AiState.ERROR
                return
            }

            audioRecord?.startRecording()
            isCapturing = true
            _aiState.value = AiState.LISTENING

            recordingJob = scope.launch(Dispatchers.IO) {
                val tempBuf = ShortArray(minBufSize / 2)
                while (isActive && isCapturing) {
                    try {
                        val readSamples = audioRecord?.read(tempBuf, 0, tempBuf.size) ?: -1
                        if (readSamples > 0) {
                            // Calculate RMS for VAD (Voice Activity Detection)
                            var sumSquare = 0.0
                            for (i in 0 until readSamples) {
                                sumSquare += (tempBuf[i] * tempBuf[i])

                                // Copy into transient buffer (ring buffer)
                                val b1 = (tempBuf[i].toInt() and 0xFF).toByte()
                                val b2 = ((tempBuf[i].toInt() shr 8) and 0xFF).toByte()
                                synchronized(transientBuffer) {
                                    transientBuffer[bufferWriteIndex] = b1
                                    bufferWriteIndex = (bufferWriteIndex + 1) % bufferSize
                                    transientBuffer[bufferWriteIndex] = b2
                                    bufferWriteIndex = (bufferWriteIndex + 1) % bufferSize
                                }
                            }
                            val rms = sqrt(sumSquare / readSamples).toFloat()
                            _audioLevelRms.value = rms
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: SecurityException) {
            _aiState.value = AiState.ERROR
        } catch (e: Exception) {
            _aiState.value = AiState.ERROR
        }
    }

    override fun stopCapture() {
        if (!isCapturing) return
        isCapturing = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            // Ignore
        } finally {
            audioRecord = null
            _aiState.value = AiState.OFF
            _audioLevelRms.value = 0f
        }
    }

    override fun getTransientPcmData(): ByteArray? {
        if (!isCapturing) return null
        synchronized(transientBuffer) {
            return transientBuffer.copyOf()
        }
    }
}
