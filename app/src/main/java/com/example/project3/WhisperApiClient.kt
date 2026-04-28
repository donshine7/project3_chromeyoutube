package com.example.project3

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(360, TimeUnit.SECONDS)
        .callTimeout(420, TimeUnit.SECONDS)
        .build()

    fun transcribeVerboseEnglish(apiKey: String, audioFile: File): WhisperVerboseResult {
        // Prefer transcription with word timestamps for stable sentence boundaries.
        val transcriptionAttempt = runCatching {
            postWhisper(
                apiKey = apiKey,
                audioFile = audioFile,
                endpoint = "https://api.openai.com/v1/audio/transcriptions",
                includeWordTimestamps = true
            )
        }
        if (transcriptionAttempt.getOrNull()?.isSuccessful == true) {
            return WhisperVerboseJsonParser.parse(JSONObject(transcriptionAttempt.getOrThrow().payload))
        }

        // Fallback: translation endpoint can still succeed on some edge formats.
        val translationAttempt = runCatching {
            postWhisper(
                apiKey = apiKey,
                audioFile = audioFile,
                endpoint = "https://api.openai.com/v1/audio/translations",
                includeWordTimestamps = false
            )
        }
        if (translationAttempt.getOrNull()?.isSuccessful == true) {
            return WhisperVerboseJsonParser.parse(JSONObject(translationAttempt.getOrThrow().payload))
        }

        val transcriptionError = transcriptionAttempt.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            ?: transcriptionAttempt.getOrNull()?.let {
                "HTTP ${it.code}: ${extractErrorMessage(it.payload)}"
            } ?: "unknown"
        val translationError = translationAttempt.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" }
            ?: translationAttempt.getOrNull()?.let {
                "HTTP ${it.code}: ${extractErrorMessage(it.payload)}"
            } ?: "unknown"
        throw IllegalStateException(
            "Whisper API failed. " +
                "transcriptions($transcriptionError); " +
                "translations($translationError)"
        )
    }

    private fun postWhisper(
        apiKey: String,
        audioFile: File,
        endpoint: String,
        includeWordTimestamps: Boolean
    ): HttpAttempt {
        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(detectMediaType(audioFile).toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
        if (includeWordTimestamps) {
            bodyBuilder.addFormDataPart("timestamp_granularities[]", "word")
        }
        val body = bodyBuilder.build()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            return HttpAttempt(
                code = response.code,
                payload = payload,
                isSuccessful = response.isSuccessful
            )
        }
    }

    private fun detectMediaType(audioFile: File): String {
        return when (audioFile.extension.lowercase()) {
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "webm" -> "audio/webm"
            "m4a", "mp4" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }
    }

    private fun extractErrorMessage(payload: String): String {
        return runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: payload.take(220)
    }

    private data class HttpAttempt(
        val code: Int,
        val payload: String,
        val isSuccessful: Boolean
    )

}
