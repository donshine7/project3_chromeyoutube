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
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    fun transcribeVerboseEnglish(apiKey: String, audioFile: File): WhisperVerboseResult {
        // Prefer transcription with word timestamps for stable sentence boundaries.
        val transcriptionAttempt = postWhisper(
            apiKey = apiKey,
            audioFile = audioFile,
            endpoint = "https://api.openai.com/v1/audio/transcriptions",
            includeWordTimestamps = true
        )
        if (transcriptionAttempt.isSuccessful) {
            return WhisperVerboseJsonParser.parse(JSONObject(transcriptionAttempt.payload))
        }

        // Fallback: translation endpoint can still succeed on some edge formats.
        val translationAttempt = postWhisper(
            apiKey = apiKey,
            audioFile = audioFile,
            endpoint = "https://api.openai.com/v1/audio/translations",
            includeWordTimestamps = false
        )
        if (translationAttempt.isSuccessful) {
            return WhisperVerboseJsonParser.parse(JSONObject(translationAttempt.payload))
        }

        throw IllegalStateException(
            "Whisper API failed. " +
                "transcriptions(${transcriptionAttempt.code}): ${extractErrorMessage(transcriptionAttempt.payload)}; " +
                "translations(${translationAttempt.code}): ${extractErrorMessage(translationAttempt.payload)}"
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
