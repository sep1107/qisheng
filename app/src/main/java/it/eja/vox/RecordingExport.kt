package it.eja.vox

import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RecordingExport {
    fun display(transcript: String, summary: String): String =
        if (summary.isBlank()) transcript else "## AI 总结\n\n$summary\n\n## 转写原文\n\n$transcript"

    fun filename(session: File, audio: Boolean): String {
        val title = SessionLibrary.title(session).ifBlank { "柒声录音-${session.name}" }
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(80).trim().trimStart('.').ifBlank { "柒声录音" }
        return "$title-${session.name}.${if (audio) "zip" else "md"}"
    }
    fun markdown(session: File): String {
        val title = SessionLibrary.title(session).replace(Regex("[\\r\\n]"), " ").ifBlank { "柒声录音" }
        val date = session.name.toLongOrNull()?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", java.util.Locale.ROOT).format(java.util.Date(it))
        }.orEmpty()
        val text = RecordingStore.transcript(session)
        val summary = RecordingStore.savedSummary(session)
        return buildString {
            append("# $title\n\n会话时间（录音开始或导入时间）：$date\n\n来源：${if (File(session, "import-complete.txt").exists()) "系统音频导入柒声" else "柒声录音"}\n\n")
            if (RecordingStore.pending(session).isNotEmpty() || (File(session, "import-incomplete.txt").exists() && !File(session, "import-complete.txt").exists())) append("> 部分分段尚未转写，以下不是完整逐字稿。\n\n")
            if (summary.isNotBlank()) append("## AI 总结\n\n$summary\n\n")
            append("## 转写原文\n\n${text.ifBlank { "尚无转写文字。" }}\n")

        }
    }
    fun write(session: File, audio: Boolean, output: OutputStream) {
        if (!audio) output.write(markdown(session).toByteArray(Charsets.UTF_8))
        else {
            val segments = RecordingStore.segments(session)
            check(segments.isNotEmpty()) { "没有完整的音频分段可导出" }
            ZipOutputStream(output).use { zip ->
            zip.setLevel(0)
            segments.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            }
        }
    }
    fun sha256(markdown: String) = MessageDigest.getInstance("SHA-256")
        .digest(markdown.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun receiverUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null && uri.query == null) {
            "接收地址须为完整 HTTPS 地址，不能带账号、查询参数或片段"
        }
        return uri.toASCIIString()
    }
}
