package it.eja.vox

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class RecordingExportTest {
    @Test fun markdownContainsPersistedTextSummaryAndWarningWithoutReadingApiSettings() {
        val session = Files.createTempDirectory("export-session").toFile()
        File(session, "name.txt").writeText("门店会议\n第二行")
        File(session, "000000.wav").writeBytes(byteArrayOf(1, 2))
        File(session, "000000.txt").writeText("今天下午三点开会。")
        File(session, "000001.wav").writeBytes(byteArrayOf(3, 4))
        File(session, "summary.txt").writeText("讨论门店视频。")
        File(session, "source.txt").writeText("PRIVATE_SOURCE_URI")
        val md = RecordingExport.markdown(session)
        assertTrue(md.contains("# 门店会议 第二行"))
        assertTrue(md.contains("讨论门店视频。"))
        assertTrue(md.contains("今天下午三点开会。"))
        assertTrue(md.contains("部分分段尚未转写"))
        assertFalse(md.contains("PRIVATE_SOURCE_URI"))
        assertFalse(md.contains("audio/"))
    }
    @Test fun audioZipPreservesAllSegmentsAndContainsOnlyAudio() {
        val session = Files.createTempDirectory("export-audio").toFile()
        File(session, "000000.wav").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(session, "000001.wav").writeBytes(byteArrayOf(5, 6))
        File(session, "summary.txt").writeText("总结")
        File(session, "source.txt").writeText("not exported")
        val out = ByteArrayOutputStream()
        RecordingExport.write(session, true, out)
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        assertEquals(setOf("000000.wav", "000001.wav"), files.keys)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), files["000000.wav"])
        assertArrayEquals(byteArrayOf(5, 6), files["000001.wav"])
        assertTrue(files.keys.none { it.endsWith(".md") || it.endsWith(".txt") })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(session, "000000.wav").readBytes())
    }
    @Test fun receiptMustMatchAndShowsSimpleSuccess() {
        assertEquals("发送成功", ReceiverReceipt.message(202, "id", "hash", "id", "hash", "queued"))
        assertEquals("发送成功", ReceiverReceipt.message(201, "id", "hash", "id", "hash", "saved"))
        assertThrows(IllegalStateException::class.java) { ReceiverReceipt.message(200, "id", "hash", "other", "hash", "saved") }
        assertThrows(IllegalStateException::class.java) { ReceiverReceipt.message(200, "id", "hash", "id", "other", "saved") }
        assertThrows(IllegalStateException::class.java) { ReceiverReceipt.message(202, "id", "hash", "id", "hash", "saved") }
    }
    @Test fun safeFilenameHttpsEndpointAndDigest() {
        val session = Files.createTempDirectory("export-name").toFile()
        File(session, "name.txt").writeText("../a/b\\c\n")
        val filename = RecordingExport.filename(session, false)
        assertFalse(filename.contains('/')); assertFalse(filename.contains('\\')); assertFalse(filename.startsWith('.'))
        assertTrue(filename.endsWith(".md"))
        assertEquals("https://receiver.example/v1/notes", RecordingExport.receiverUrl(" https://receiver.example/v1/notes "))
        listOf("http://receiver.example", "https://user:secret@receiver.example", "https://receiver.example/?token=secret", "https://receiver.example/#note").forEach {
            assertThrows(IllegalArgumentException::class.java) { RecordingExport.receiverUrl(it) }
        }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", RecordingExport.sha256(""))
    }
}
