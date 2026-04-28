package com.example.project3

import android.content.ClipboardManager
import android.content.Context

object YoutubeClipboardReader {
    fun readYoutubeUrl(context: Context): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = clipboard.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i)?.coerceToText(context)?.toString()
            val normalized = YoutubeUrlParser.normalizeUrl(text)
            if (normalized != null) return normalized
        }
        return null
    }
}
