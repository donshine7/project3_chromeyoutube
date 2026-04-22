package com.example.project3

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SentenceTimestampStore(context: Context) {
    private val root = File(context.filesDir, "stt_results").apply { mkdirs() }

    data class FolderEntry(val date: String, val contentId: String, val path: File)

    fun save(youtubeUrl: String, sentences: List<SentenceTimestamp>): File {
        val now = Date()
        val dateFolder = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val canonicalUrl = YoutubeUrlParser.canonicalWatchUrlFromAny(youtubeUrl) ?: youtubeUrl
        val videoId = YoutubeUrlParser.extractVideoId(canonicalUrl) ?: "unknown_video"
        val dateDir = File(root, dateFolder).apply { mkdirs() }
        val contentDir = resolveContentDir(dateDir, canonicalUrl, videoId, now)
        val file = File(contentDir, "sentences-${SimpleDateFormat("HHmmss", Locale.US).format(now)}.json")
        val merged = (loadLatestSentences(contentDir) + sentences)
            .distinctBy { "${it.startSec}|${it.endSec}|${it.text}" }
            .sortedBy { it.startSec }
        val payload = JSONObject().apply {
            put("youtubeUrl", canonicalUrl)
            put("savedAt", System.currentTimeMillis())
            put("videoId", videoId)
            put("timestampsAbsolute", true)
            put("sentences", JSONArray().apply {
                merged.forEach { s ->
                    put(JSONObject().apply {
                        put("startSec", s.startSec)
                        put("endSec", s.endSec)
                        put("text", s.text)
                    })
                }
            })
        }
        file.writeText(payload.toString(2))
        return file
    }

    fun listDateContentFolders(): List<FolderEntry> {
        if (!root.exists()) return emptyList()
        return root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.flatMap { dateDir ->
            dateDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.map { contentDir ->
                FolderEntry(dateDir.name, contentDir.name, contentDir)
            } ?: emptyList()
        } ?: emptyList()
    }

    fun loadSentences(folder: FolderEntry): List<SentenceTimestamp> {
        val latest = latestJson(folder.path) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(latest.readText()).optJSONArray("sentences") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        SentenceTimestamp(
                            startSec = item.optDouble("startSec", 0.0),
                            endSec = item.optDouble("endSec", 0.0),
                            text = item.optString("text", "")
                        )
                    )
                }
            }.sortedBy { it.startSec }
        }.getOrElse { emptyList() }
    }

    fun loadYoutubeUrl(folder: FolderEntry): String? {
        val latest = latestJson(folder.path) ?: return null
        val raw = runCatching { JSONObject(latest.readText()).optString("youtubeUrl") }.getOrNull()
        return YoutubeUrlParser.canonicalWatchUrlFromAny(raw)
    }

    fun deleteSentence(folder: FolderEntry, target: SentenceTimestamp): Boolean {
        val latest = latestJson(folder.path) ?: return false
        val rootJson = runCatching { JSONObject(latest.readText()) }.getOrDefault(JSONObject())
        val keep = loadSentences(folder)
            .filterNot { it.startSec == target.startSec && it.endSec == target.endSec && it.text == target.text }
            .sortedBy { it.startSec }
        rootJson.put("timestampsAbsolute", true)
        rootJson.put("sentences", JSONArray().apply {
            keep.forEach { s ->
                put(JSONObject().apply {
                    put("startSec", s.startSec)
                    put("endSec", s.endSec)
                    put("text", s.text)
                })
            }
        })
        latest.writeText(rootJson.toString(2))
        return true
    }

    fun deleteContentFolder(folder: FolderEntry): Boolean = folder.path.deleteRecursively()

    fun deleteDateFolder(date: String): Boolean {
        val dateDir = File(root, date)
        return dateDir.exists() && dateDir.isDirectory && dateDir.deleteRecursively()
    }

    private fun latestJson(contentDir: File): File? =
        contentDir.listFiles()?.filter { it.isFile && it.extension.equals("json", true) }?.maxByOrNull { it.lastModified() }

    private fun loadLatestSentences(contentDir: File): List<SentenceTimestamp> {
        val latest = latestJson(contentDir) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(latest.readText()).optJSONArray("sentences") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        SentenceTimestamp(
                            startSec = item.optDouble("startSec", 0.0),
                            endSec = item.optDouble("endSec", 0.0),
                            text = item.optString("text", "")
                        )
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun resolveContentDir(dateDir: File, canonicalUrl: String, videoId: String, now: Date): File {
        val existing = dateDir.listFiles()?.firstOrNull { dir ->
            if (!dir.isDirectory) return@firstOrNull false
            val latest = latestJson(dir) ?: return@firstOrNull false
            val saved = runCatching { JSONObject(latest.readText()).optString("youtubeUrl") }.getOrNull()
            YoutubeUrlParser.canonicalWatchUrlFromAny(saved) == canonicalUrl
        }
        if (existing != null) return existing
        val preferred = File(dateDir, videoId)
        if (!preferred.exists()) return preferred.apply { mkdirs() }
        val suffix = SimpleDateFormat("HHmmss", Locale.US).format(now)
        return File(dateDir, "${videoId}_$suffix").apply { mkdirs() }
    }
}
