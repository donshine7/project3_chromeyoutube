package com.example.project3

import kotlin.math.max
import kotlin.math.min

class SentenceAssembler {
    fun build(result: WhisperVerboseResult, requestedStartSec: Double, requestedEndSec: Double): List<SentenceTimestamp> {
        val fromWords = result.words.orEmpty()
        val sentences = if (fromWords.isNotEmpty()) splitWords(fromWords) else splitSegments(result.segments.orEmpty())
        return sentences.mapNotNull { keepIfRelevant(it, requestedStartSec, requestedEndSec) }
    }

    private fun splitWords(words: List<WhisperWord>): List<SentenceTimestamp> {
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<SentenceTimestamp>()
        val current = mutableListOf<WhisperWord>()

        for (word in words) {
            if (current.isNotEmpty()) {
                val prev = current.last()
                val gap = word.start - prev.end
                val closeByPunctuation = endsSentence(prev.word)
                if (gap >= 0.9 || closeByPunctuation || current.size >= 40) {
                    emitWords(current)?.let(out::add)
                    current.clear()
                }
            }
            current.add(word)
        }
        emitWords(current)?.let(out::add)
        return out
    }

    private fun splitSegments(segments: List<WhisperSegment>): List<SentenceTimestamp> {
        return segments.mapNotNull { seg ->
            val text = normalizeText(seg.text)
            if (text.isBlank()) null else SentenceTimestamp(seg.start, seg.end, text)
        }
    }

    private fun emitWords(words: List<WhisperWord>): SentenceTimestamp? {
        if (words.isEmpty()) return null
        val text = normalizeText(words.joinToString(separator = "") { it.word })
        if (text.isBlank()) return null
        return SentenceTimestamp(words.first().start, words.last().end, text)
    }

    private fun keepIfRelevant(
        sentence: SentenceTimestamp,
        requestedStartSec: Double,
        requestedEndSec: Double
    ): SentenceTimestamp? {
        if (sentence.endSec <= requestedStartSec || sentence.startSec >= requestedEndSec) return null
        return sentence.copy(
            startSec = max(requestedStartSec, sentence.startSec),
            endSec = min(requestedEndSec, sentence.endSec)
        )
    }

    private fun normalizeText(value: String): String {
        return value.replace("\\s+".toRegex(), " ").trim()
    }

    private fun endsSentence(word: String): Boolean {
        return word.trimEnd().endsWith(".")
            || word.trimEnd().endsWith("?")
            || word.trimEnd().endsWith("!")
    }
}
