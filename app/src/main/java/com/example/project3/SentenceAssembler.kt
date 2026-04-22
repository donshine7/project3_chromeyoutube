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
                if (shouldSplit(current, prev, word)) {
                    emitWords(current)?.let(out::add)
                    current.clear()
                }
            }
            current.add(word)
        }
        emitWords(current)?.let(out::add)
        return mergeSmallFragments(out)
    }

    private fun splitSegments(segments: List<WhisperSegment>): List<SentenceTimestamp> {
        val raw = segments.mapNotNull { seg ->
            val text = normalizeText(seg.text)
            if (text.isBlank()) null else SentenceTimestamp(seg.start, seg.end, text)
        }
        if (raw.size <= 1) return raw

        val merged = mutableListOf<SentenceTimestamp>()
        var current = raw.first()
        for (i in 1 until raw.size) {
            val next = raw[i]
            val gap = next.startSec - current.endSec
            val shouldMerge = !endsSentence(current.text) && gap <= 1.2
            if (shouldMerge) {
                current = SentenceTimestamp(
                    startSec = current.startSec,
                    endSec = next.endSec,
                    text = normalizeText("${current.text} ${next.text}")
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return mergeSmallFragments(merged)
    }

    private fun emitWords(words: List<WhisperWord>): SentenceTimestamp? {
        if (words.isEmpty()) return null
        val text = normalizeText(words.joinToString(separator = "") { it.word })
        if (text.isBlank()) return null
        return SentenceTimestamp(words.first().start, words.last().end, text)
    }

    private fun shouldSplit(current: List<WhisperWord>, prev: WhisperWord, next: WhisperWord): Boolean {
        val gap = next.start - prev.end
        val span = current.last().end - current.first().start
        val punctuationSplit = endsSentence(prev.word) && current.size >= 6
        val silenceSplit = gap >= 1.4 && current.size >= 5
        val veryLongSilenceSplit = gap >= 2.2
        val tooLongSentence = current.size >= 55 || span >= 18.0
        return punctuationSplit || silenceSplit || veryLongSilenceSplit || tooLongSentence
    }

    private fun mergeSmallFragments(items: List<SentenceTimestamp>): List<SentenceTimestamp> {
        if (items.size <= 1) return items
        val merged = mutableListOf<SentenceTimestamp>()
        var i = 0
        while (i < items.size) {
            val cur = items[i]
            val dur = cur.endSec - cur.startSec
            val isTiny = cur.text.length < 20 || dur < 1.2
            if (isTiny && i + 1 < items.size) {
                val next = items[i + 1]
                val joinedText = normalizeText("${cur.text} ${next.text}")
                merged += SentenceTimestamp(
                    startSec = cur.startSec,
                    endSec = next.endSec,
                    text = joinedText
                )
                i += 2
                continue
            }
            merged += cur
            i += 1
        }
        return merged
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
