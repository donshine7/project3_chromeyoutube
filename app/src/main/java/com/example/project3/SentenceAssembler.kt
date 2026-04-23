package com.example.project3

import kotlin.math.max
import kotlin.math.min

class SentenceAssembler {
    fun build(result: WhisperVerboseResult, requestedStartSec: Double, requestedEndSec: Double): List<SentenceTimestamp> {
        val fromWords = result.words.orEmpty()
        val segments = result.segments.orEmpty()
        val sentences = if (fromWords.isNotEmpty()) splitWords(fromWords, segments) else splitSegments(segments)
        val clipped = sentences.mapNotNull { keepIfRelevant(it, requestedStartSec, requestedEndSec) }
        return if (fromWords.isNotEmpty() && segments.isNotEmpty()) {
            restoreTextFromSegments(clipped, segments)
        } else {
            clipped
        }
    }

    private fun splitWords(words: List<WhisperWord>, segments: List<WhisperSegment>): List<SentenceTimestamp> {
        if (words.isEmpty()) return emptyList()
        val out = mutableListOf<SentenceTimestamp>()
        val current = mutableListOf<WhisperWord>()
        val punctuationBoundaries = buildPunctuationBoundaries(segments)

        for (word in words) {
            if (current.isNotEmpty()) {
                val prev = current.last()
                if (shouldSplit(current, prev, word, punctuationBoundaries)) {
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
            val curDuration = current.endSec - current.startSec
            val shouldMerge = !endsSentence(current.text) && curDuration < 1.0 && gap <= 0.35
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
        val text = normalizeText(rebuildTextFromWords(words))
        if (text.isBlank()) return null
        return SentenceTimestamp(words.first().start, words.last().end, text)
    }

    private fun rebuildTextFromWords(words: List<WhisperWord>): String {
        val out = StringBuilder()
        words.forEach { token ->
            val raw = token.word
            val piece = raw.trim()
            if (piece.isBlank()) return@forEach
            if (out.isEmpty()) {
                out.append(piece)
                return@forEach
            }
            val noLeadingSpace = piece.firstOrNull()?.let { isPunctuation(it) } == true || startsWithApostropheToken(piece)
            if (!noLeadingSpace) out.append(' ')
            out.append(piece)
        }
        return out.toString()
    }

    private fun restoreTextFromSegments(
        items: List<SentenceTimestamp>,
        segments: List<WhisperSegment>
    ): List<SentenceTimestamp> {
        if (items.isEmpty() || segments.isEmpty()) return items
        return items.map { sentence ->
            val joined = segments.asSequence()
                .filter { segment ->
                    if (segment.end <= sentence.startSec || segment.start >= sentence.endSec) return@filter false
                    val overlapStart = max(sentence.startSec, segment.start)
                    val overlapEnd = min(sentence.endSec, segment.end)
                    val overlap = (overlapEnd - overlapStart).coerceAtLeast(0.0)
                    if (overlap <= 0.0) return@filter false
                    val segDur = (segment.end - segment.start).coerceAtLeast(0.01)
                    val overlapRatio = overlap / segDur
                    val center = (segment.start + segment.end) / 2.0
                    overlapRatio >= 0.55 || center in sentence.startSec..sentence.endSec
                }
                .map { it.text }
                .joinToString(" ")
                .let(::normalizeText)
            if (joined.isBlank()) sentence else sentence.copy(text = joined)
        }
    }

    private fun shouldSplit(
        current: List<WhisperWord>,
        prev: WhisperWord,
        next: WhisperWord,
        punctuationBoundaries: List<Double>
    ): Boolean {
        val gap = next.start - prev.end
        val span = current.last().end - current.first().start
        val hasSegmentPunctuationBoundary = punctuationBoundaries.any { boundary ->
            boundary in (prev.end - 0.35)..(next.start + 0.35)
        }
        val punctuationSplit = (endsSentence(prev.word) || hasSegmentPunctuationBoundary) && current.size >= 2
        val silenceSplit = gap >= 0.85 && current.size >= 2
        val veryLongSilenceSplit = gap >= 2.2
        val tooLongSentence = current.size >= 35 || span >= 10.0
        return punctuationSplit || silenceSplit || veryLongSilenceSplit || tooLongSentence
    }

    private fun buildPunctuationBoundaries(segments: List<WhisperSegment>): List<Double> {
        if (segments.isEmpty()) return emptyList()
        return segments.asSequence()
            .filter { endsSentence(it.text) }
            .map { it.end }
            .toList()
    }

    private fun mergeSmallFragments(items: List<SentenceTimestamp>): List<SentenceTimestamp> {
        if (items.size <= 1) return items
        val merged = mutableListOf<SentenceTimestamp>()
        var i = 0
        while (i < items.size) {
            val cur = items[i]
            val dur = cur.endSec - cur.startSec
            val isTiny = cur.text.length < 12 && dur < 0.9
            if (isTiny && i + 1 < items.size && !endsSentence(cur.text)) {
                val next = items[i + 1]
                val gap = next.startSec - cur.endSec
                val canJoin = gap <= 0.45 && !startsStrongSentence(next.text)
                if (!canJoin) {
                    merged += cur
                    i += 1
                    continue
                }
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

    private fun startsStrongSentence(text: String): Boolean {
        val normalized = text.trimStart()
        if (normalized.isBlank()) return false
        return normalized.startsWith("\"")
            || normalized.startsWith("'")
            || normalized.first().isUpperCase()
    }

    private fun isPunctuation(ch: Char): Boolean {
        return ch == '.' || ch == ',' || ch == '!' || ch == '?' || ch == ':' || ch == ';' || ch == ')'
    }

    private fun startsWithApostropheToken(piece: String): Boolean {
        return piece.startsWith("'") || piece.startsWith("’")
    }
}
