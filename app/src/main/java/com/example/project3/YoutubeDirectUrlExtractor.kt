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
    val liveStatus: String?
)

class YoutubeDirectUrlExtractor(private val context: Context) {
    fun downloadToLocal(sourceUrl: String): DownloadedAudioSource {
        startPythonIfNeeded()
        val py = Python.getInstance()
        val bridge = py.getModule("yt_dlp_bridge")
        val outDir = context.getExternalFilesDir(null)?.absolutePath
            ?: throw IllegalStateException("external files dir unavailable")

        val result: PyObject = bridge.callAttr("download_audio_file", sourceUrl, outDir)
        val json = JSONObject(result.toString())
        return DownloadedAudioSource(
            path = json.getString("path"),
            fromCache = json.optBoolean("fromCache", false),
            cacheAgeSec = json.optLong("cacheAgeSec", -1L).takeIf { it >= 0L },
            liveStatus = json.optString("liveStatus").ifBlank { null }
        )
    }

    private fun startPythonIfNeeded() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }
}
