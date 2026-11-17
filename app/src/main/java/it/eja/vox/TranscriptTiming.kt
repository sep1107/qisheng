package it.eja.vox

import java.io.File

object TranscriptTiming {
    fun offset(audio: File): Long = (audio.nameWithoutExtension.toLongOrNull() ?: 0).coerceAtLeast(0) * 120_000L
    fun stamp(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return "[%02d:%02d:%02d]".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    fun line(offset: Long, start: Double, text: String, speaker: String = "", segment: String = ""): String {
        if (text.isBlank()) return ""
        val time = if (start.isFinite() && start >= 0) stamp(offset + (start * 1000).toLong()) + " " else ""
        val label = speaker.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,32}")) }
            ?.let { "分段$segment·说话人$it：" }.orEmpty()
        return time + label + text.trim()
    }
}
