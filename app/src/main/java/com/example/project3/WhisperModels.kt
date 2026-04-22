package com.example.project3

data class WhisperWord(
    val word: String,
    val start: Double,
    val end: Double
)

data class WhisperSegment(
    val id: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val words: List<WhisperWord>?
)

data class WhisperVerboseResult(
    val text: String,
    val language: String?,
    val duration: Double?,
    val segments: List<WhisperSegment>?,
    val words: List<WhisperWord>?
)
