package com.example.project3

import android.content.Context
import java.io.File

object OnDeviceWhisperModelStore {
    private const val ASSET_MODEL_TINY_PATH = "models/ggml-tiny-q8_0.bin"
    private const val ASSET_MODEL_BASE_PATH = "models/ggml-base-q8_0.bin"
    private const val MIN_MODEL_BYTES = 10L * 1024L * 1024L

    fun ensureModelReady(context: Context): String {
        val modelDir = File(context.filesDir, "models").apply { mkdirs() }
        val candidateAssets = listOf(
            ASSET_MODEL_TINY_PATH to "ggml-tiny-q8_0.bin",
            ASSET_MODEL_BASE_PATH to "ggml-base-q8_0.bin"
        )
        val tinyTarget = File(modelDir, "ggml-tiny-q8_0.bin")
        val baseTarget = File(modelDir, "ggml-base-q8_0.bin")

        if (tinyTarget.exists() && tinyTarget.length() >= MIN_MODEL_BYTES) return tinyTarget.absolutePath
        if (baseTarget.exists() && baseTarget.length() >= MIN_MODEL_BYTES) return baseTarget.absolutePath

        val availableAssets = context.assets.list("models")?.toSet().orEmpty()
        for ((assetPath, fileName) in candidateAssets) {
            val assetName = assetPath.substringAfter("models/")
            if (!availableAssets.contains(assetName)) continue
            val target = File(modelDir, fileName)
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists() && target.length() >= MIN_MODEL_BYTES) {
                return target.absolutePath
            }
        }

        val tinySize = tinyTarget.takeIf { it.exists() }?.length() ?: 0L
        val baseSize = baseTarget.takeIf { it.exists() }?.length() ?: 0L
        throw IllegalStateException(
            "On-device model is missing or invalid. " +
                "Expected assets/models/ggml-tiny-q8_0.bin or ggml-base-q8_0.bin " +
                "(>=${MIN_MODEL_BYTES} bytes). current tiny=$tinySize, base=$baseSize"
        )
    }
}
