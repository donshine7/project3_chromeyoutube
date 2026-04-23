package com.example.project3

import android.net.Uri

object YoutubeUrlParser {
    fun normalizeUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        val direct = canonicalWatchUrl(extractVideoId(trimmed))
        if (direct != null) return direct

        // Chrome accessibility text often omits scheme (e.g. youtube.com/watch?v=...).
        val fromText = Regex(
            """((?:https?://)?(?:www\.)?(?:m\.)?(?:youtube\.com|youtu\.be)/[^\s]+)""",
            RegexOption.IGNORE_CASE
        ).find(trimmed)?.value?.trimEnd('.', ',', ')', ']', '}', '>')
        return canonicalWatchUrl(extractVideoId(fromText))
    }

    fun canonicalWatchUrl(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://www.youtube.com/watch?v=$videoId"
    }

    fun canonicalWatchUrlFromAny(raw: String?): String? {
        return canonicalWatchUrl(extractVideoId(raw))
    }

    fun extractVideoId(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val candidate = rawUrl.trim()
        val uri = parseYoutubeUri(candidate) ?: return null
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

    private fun parseYoutubeUri(raw: String): Uri? {
        val direct = runCatching { Uri.parse(raw) }.getOrNull()
        val directHost = direct?.host?.lowercase().orEmpty()
        if (direct != null && (directHost.contains("youtube.com") || directHost.endsWith("youtu.be"))) {
            return direct
        }
        val withScheme = runCatching {
            if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) Uri.parse(raw)
            else Uri.parse("https://$raw")
        }.getOrNull()
        val host = withScheme?.host?.lowercase().orEmpty()
        return withScheme?.takeIf { host.contains("youtube.com") || host.endsWith("youtu.be") }
    }

    fun extractSeconds(rawUrl: String?): Int {
        if (rawUrl.isNullOrBlank()) return 0
        val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return 0
        val candidate = uri.getQueryParameter("t") ?: uri.getQueryParameter("start") ?: return 0
        val trimmed = candidate.trim().lowercase()
        if (trimmed.isEmpty()) return 0
        return if (trimmed.endsWith("s")) {
            trimmed.removeSuffix("s").toIntOrNull() ?: 0
        } else {
            trimmed.toIntOrNull() ?: 0
        }.coerceAtLeast(0)
    }
}
