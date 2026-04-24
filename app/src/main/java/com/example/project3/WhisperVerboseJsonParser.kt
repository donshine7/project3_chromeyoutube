package com.example.project3

import org.json.JSONArray
import org.json.JSONObject

object WhisperVerboseJsonParser {
    fun parse(payload: String): WhisperVerboseResult {
        return parse(JSONObject(payload))
    }

    fun parse(json: JSONObject): WhisperVerboseResult {
        val languageValue = if (json.has("language")) json.optString("language") else null
        return WhisperVerboseResult(
            text = json.optString("text", ""),
            language = languageValue,
            duration = json.optDouble("duration", Double.NaN).takeUnless { it.isNaN() },
            segments = json.optJSONArray("segments")?.let(::parseSegments),
            words = json.optJSONArray("words")?.let(::parseWords)
        )
    }

    private fun parseSegments(arr: JSONArray): List<WhisperSegment> {
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    WhisperSegment(
                        id = obj.optInt("id", i),
                        start = obj.optDouble("start", 0.0),
                        end = obj.optDouble("end", 0.0),
                        text = obj.optString("text", ""),
                        words = obj.optJSONArray("words")?.let(::parseWords)
                    )
                )
            }
        }
    }

    private fun parseWords(arr: JSONArray): List<WhisperWord> {
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    WhisperWord(
                        word = obj.optString("word", ""),
                        start = obj.optDouble("start", 0.0),
                        end = obj.optDouble("end", 0.0)
                    )
                )
            }
        }
    }
}
