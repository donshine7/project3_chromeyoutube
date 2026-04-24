package com.example.project3

class WhisperJniBridge {
    init {
        if (!isNativeAvailable()) {
            throw IllegalStateException(
                "whisper_jni is unavailable. Build with -PenableOnDeviceNative=true " +
                    "after setting up whisper.cpp native integration."
            )
        }
    }

    external fun transcribeVerboseJson(
        modelPath: String,
        audioPath: String,
        language: String,
        enableWordTimestamps: Boolean
    ): String

    companion object {
        @Volatile
        private var loaded: Boolean? = null

        fun isNativeAvailable(): Boolean {
            val cached = loaded
            if (cached != null) return cached
            return runCatching {
                System.loadLibrary("whisper_jni")
                true
            }.getOrDefault(false).also { loaded = it }
        }
    }
}
