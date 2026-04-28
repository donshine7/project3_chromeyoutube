package com.example.project3

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ChromeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        val packageName = ev.packageName?.toString().orEmpty()
        if (packageName.isBlank() || !packageName.contains("chrome", ignoreCase = true)) return

        extractUrlFromEvent(ev)?.let { url ->
            YoutubeUrlParser.normalizeUrl(url)?.let { normalized ->
                ChromeCaptureStore.saveObservedUrl(this, normalized)
            }
        }
    }

    override fun onInterrupt() = Unit

    private fun extractUrlFromEvent(event: AccessibilityEvent): String? {
        event.text?.firstNotNullOfOrNull { text ->
            YoutubeUrlParser.normalizeUrl(text?.toString())
        }?.let { return it }

        val root = rootInActiveWindow ?: return null
        return findUrlInNode(root)
    }

    private fun findUrlInNode(node: AccessibilityNodeInfo?): String? {
        val current = node ?: return null
        val candidates = listOfNotNull(
            current.text?.toString(),
            current.contentDescription?.toString()
        )
        for (candidate in candidates) {
            YoutubeUrlParser.normalizeUrl(candidate)?.let { return it }
        }
        for (i in 0 until current.childCount) {
            val found = findUrlInNode(current.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
