package com.example.project3

import android.content.Context
import java.io.File

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
    private val bridge: WhisperJniBridge = WhisperJniBridge()
) : SttEngine {
    override fun transcribeVerboseEnglish(apiKey: String?, audioFile: File): WhisperVerboseResult {
        val modelPath = OnDeviceWhisperModelStore.ensureModelReady(context)
        val payload = bridge.transcribeVerboseJson(
            modelPath = modelPath,
            audioPath = audioFile.absolutePath,
            language = "en",
            enableWordTimestamps = true
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

    fun create(context: Context, mode: String): SttEngine {
        return if (mode == MODE_ON_DEVICE) {
            OnDeviceWhisperEngine(context)
        } else {
            ApiSttEngine()
        }
    }
}
