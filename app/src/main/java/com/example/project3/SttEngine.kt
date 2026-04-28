package com.example.project3

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

interface SttEngine {
    fun transcribeVerboseEnglish(apiKey: String?, audioFile: File): WhisperVerboseResult
}

class ApiSttEngine(
    private val apiClient: WhisperApiClient = WhisperApiClient()
) : SttEngine {
    override fun transcribeVerboseEnglish(apiKey: String?, audioFile: File): WhisperVerboseResult {
        val key = apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OpenAI API key is required for API mode.")
        return apiClient.transcribeVerboseEnglish(key, audioFile)
    }
}

class OnDeviceWhisperEngine(
    private val context: Context,
    private val profile: String,
    private val bridge: WhisperJniBridge = WhisperJniBridge()
) : SttEngine {
    override fun transcribeVerboseEnglish(apiKey: String?, audioFile: File): WhisperVerboseResult {
        val modelPath = OnDeviceWhisperModelStore.ensureModelReady(context)
        val payload = bridge.transcribeVerboseJson(
            modelPath = modelPath,
            audioPath = audioFile.absolutePath,
            language = "en",
            enableWordTimestamps = true,
            nThreads = if (profile == SttEngineFactory.ON_DEVICE_PROFILE_FAST) 6 else 4
        )
        if (payload.contains("\"error\"")) {
            throw IllegalStateException("On-device whisper error: $payload")
        }
        val result = WhisperVerboseJsonParser.parse(payload)
        if (result.words.isNullOrEmpty()) {
            throw IllegalStateException("On-device Whisper did not return word timestamps.")
        }
        return result
    }
}

object SttEngineFactory {
    const val MODE_API = "api"
    const val MODE_ON_DEVICE = "on_device"
    const val ON_DEVICE_PROFILE_ACCURATE = "accurate"
    const val ON_DEVICE_PROFILE_FAST = "fast"

    fun create(context: Context, mode: String, onDeviceProfile: String): SttEngine {
        return if (mode == MODE_ON_DEVICE) {
            OnDeviceWhisperEngine(context, onDeviceProfile)
        } else {
            ApiSttEngine()
        }
    }
}

object OnDeviceWarmupCoordinator {
    private const val TAG = "OnDeviceWarmup"
    private val warmed = AtomicBoolean(false)

    fun ensureWarmupOnce(context: Context, profile: String) {
        if (warmed.get()) return
        synchronized(this) {
            if (warmed.get()) return
            runCatching {
                val modelPath = OnDeviceWhisperModelStore.ensureModelReady(context.applicationContext)
                val bridge = WhisperJniBridge()
                val wav = ensureSilentWarmupWav(context.applicationContext)
                val nThreads = if (profile == SttEngineFactory.ON_DEVICE_PROFILE_FAST) 6 else 4
                val payload = bridge.transcribeVerboseJson(
                    modelPath = modelPath,
                    audioPath = wav.absolutePath,
                    language = "en",
                    enableWordTimestamps = false,
                    nThreads = nThreads
                )
                if (payload.contains("\"error\"")) {
                    throw IllegalStateException(payload)
                }
            }.onSuccess {
                warmed.set(true)
                Log.i(TAG, "warmup done")
            }.onFailure { err ->
                Log.w(TAG, "warmup failed (non-fatal): ${err.message}")
            }
        }
    }

    private fun ensureSilentWarmupWav(context: Context): File {
        val file = File(context.cacheDir, "on_device_warmup_1s.wav")
        if (file.exists() && file.length() > 44L) return file
        val sampleRate = 16_000
        val channels = 1
        val bitsPerSample = 16
        val seconds = 1
        val dataBytes = sampleRate * channels * (bitsPerSample / 8) * seconds
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeBytes("RIFF")
            out.writeIntLE(36 + dataBytes)
            out.writeBytes("WAVE")
            out.writeBytes("fmt ")
            out.writeIntLE(16)
            out.writeShortLE(1)
            out.writeShortLE(channels.toShort())
            out.writeIntLE(sampleRate)
            out.writeIntLE(sampleRate * channels * (bitsPerSample / 8))
            out.writeShortLE((channels * (bitsPerSample / 8)).toShort())
            out.writeShortLE(bitsPerSample.toShort())
            out.writeBytes("data")
            out.writeIntLE(dataBytes)
            repeat(dataBytes) { out.writeByte(0) }
        }
        return file
    }

    private fun DataOutputStream.writeIntLE(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value ushr 8) and 0xFF)
        writeByte((value ushr 16) and 0xFF)
        writeByte((value ushr 24) and 0xFF)
    }

    private fun DataOutputStream.writeShortLE(value: Short) {
        val v = value.toInt() and 0xFFFF
        writeByte(v and 0xFF)
        writeByte((v ushr 8) and 0xFF)
    }
}
