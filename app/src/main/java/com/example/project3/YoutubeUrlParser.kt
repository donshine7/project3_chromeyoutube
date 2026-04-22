package com.example.project3

import android.net.Uri

object YoutubeUrlParser {
    fun normalizeUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        val direct = canonicalWatchUrl(extractVideoId(trimmed))
        if (direct != null) return direct

        val fromText = Regex("""(https?://[^\s]+)""").find(trimmed)?.value
        return canonicalWatchUrl(extractVideoId(fromText))
    }

    fun canonicalWatchUrl(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://www.youtube.com/watch?v=$videoId"
    }

    fun extractVideoId(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()

        return when {
            host.endsWith("youtu.be") -> uri.pathSegments.firstOrNull()
            host.contains("youtube.com") -> {
                val v = uri.getQueryParameter("v")
                if (!v.isNullOrBlank()) v
                else {
                    val segments = uri.pathSegments
                    when (segments.firstOrNull()) {
                        "shorts", "embed", "live" -> segments.getOrNull(1)
                        else -> null
                    }
                }
            }
            else -> null
        }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }
}
