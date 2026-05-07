package com.example.project3

data class SentenceTimestamp(
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val isTagged: Boolean = false,
    val taggedAtMs: Long = 0L
)
