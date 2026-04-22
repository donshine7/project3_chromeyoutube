package com.example.project3

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import java.io.File

class AudioSegmentDownloader(private val context: Context) {
    fun cutSegmentFromLocal(localSourcePath: String, startSec: Int, endSec: Int): File {
        require(endSec > startSec) { "endSec must be greater than startSec" }
        val outputDir = File(context.getExternalFilesDir(null), "segments").apply { mkdirs() }
        val outFile = File(outputDir, "seg-${System.currentTimeMillis()}-${startSec}s-${endSec}s.webm")
        val localIn = escapeForCli(localSourcePath)
        val durationSec = endSec - startSec
        val coarseSeekSec = (startSec - 3).coerceAtLeast(0)
        val fineSeekSec = startSec - coarseSeekSec

        // Hybrid seek:
        // 1) coarse seek before input to avoid decoding from 0 for late timestamps
        // 2) fine seek after input to preserve cut accuracy
        val cmd = buildString {
            append("-y -hide_banner -loglevel error -nostdin ")
            append("-ss $coarseSeekSec -i \"$localIn\" -ss $fineSeekSec -t $durationSec -vn -c:a libopus -b:a 64k -f webm ")
            append("-af \"asetpts=PTS-STARTPTS\" \"${escapeForCli(outFile.absolutePath)}\"")
        }
        val session = FFmpegKit.execute(cmd)
        if (!session.returnCode.isValueSuccess || !outFile.exists() || outFile.length() <= 1024L) {
            val logs = runCatching { session.allLogsAsString }.getOrNull().orEmpty()
            throw IllegalStateException("ffmpeg failed: ${logs.takeLast(600)}")
        }
        return outFile
    }

    private fun escapeForCli(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
