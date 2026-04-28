package com.example.project3

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

data class DownloadedAudioSource(
    val path: String,
    val fromCache: Boolean,
    val cacheAgeSec: Long?,
    val liveStatus: String?,
    val title: String?
)

class YoutubeDirectUrlExtractor(private val context: Context) {
    fun downloadToLocal(sourceUrl: String, forceDownload: Boolean = false): DownloadedAudioSource {
        return runCatching {
            startPythonIfNeeded()
            val py = Python.getInstance()
            val bridge = py.getModule("yt_dlp_bridge")
            val outDir = context.getExternalFilesDir(null)?.absolutePath
                ?: throw IllegalStateException("external files dir unavailable")

            val result: PyObject = bridge.callAttr("download_audio_file", sourceUrl, outDir, forceDownload)
            val json = JSONObject(result.toString())
            DownloadedAudioSource(
                path = json.getString("path"),
                fromCache = json.optBoolean("fromCache", false),
                cacheAgeSec = json.optLong("cacheAgeSec", -1L).takeIf { it >= 0L },
                liveStatus = json.optString("liveStatus").ifBlank { null },
                title = json.optString("title").ifBlank { null }
            )
        }.getOrElse { err ->
            val root = generateSequence(err) { it.cause }.last()
            val detail = buildString {
                append(root.javaClass.simpleName.ifBlank { "UnknownError" })
                val message = root.message?.trim().orEmpty()
                if (message.isNotBlank()) {
                    append(": ")
                    append(message.replace("\n", " ").take(280))
                }
            }
            throw IllegalStateException("yt-dlp local download failed ($detail)", err)
        }
    }

    private fun startPythonIfNeeded() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }
}
