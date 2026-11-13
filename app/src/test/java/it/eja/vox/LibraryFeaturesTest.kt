package it.eja.vox

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File

class LibraryFeaturesTest {
    @Test fun savedSummaryIsReusedUntilExplicitRegeneration() {
        val root = Files.createTempDirectory("summary-cache").toFile()
        var calls = 0
        fun generate(): String { calls++; return "第${calls}次总结" }
        assertEquals("第1次总结", RecordingStore.summary(root, generate = ::generate))
        assertEquals("第1次总结", RecordingStore.summary(root, generate = ::generate))
        assertEquals(1, calls)
        assertEquals("第2次总结", RecordingStore.summary(root, true, ::generate))
        assertEquals("第2次总结", RecordingStore.savedSummary(root))
        assertEquals(2, calls)
    }
    @Test fun failedSummaryRefreshPreservesSavedResultAndBlankCacheDoesNotCount() {
        val root = Files.createTempDirectory("summary-failure").toFile()
        File(root, "summary.txt").writeText(" ")
        assertEquals("已有总结", RecordingStore.summary(root) { "已有总结" })
        assertThrows(IllegalStateException::class.java) {
            RecordingStore.summary(root, true) { error("network failed") }
        }
        assertEquals("已有总结", RecordingStore.savedSummary(root))
    }

    @Test fun emptySavedResultsRemainPendingAndNeverBecomeTimestampOnlyTranscript() {
        val root = Files.createTempDirectory("empty-results").toFile()
        File(root, "000000.wav").writeBytes(byteArrayOf(1))
        File(root, "000000.txt").writeText("  \n")
        assertEquals(1, RecordingStore.pending(root).size)
        assertEquals("", RecordingStore.transcript(root))
        assertThrows(ApiUnavailable::class.java) { RecordingStore.requireTranscript(" \n") }
        RecordingStore.atomicText(File(root, "000000.txt"), "今天开会")
        assertTrue(RecordingStore.pending(root).isEmpty())
        assertEquals("[0:00]\n今天开会", RecordingStore.transcript(root))
    }
    @Test fun purgeOnlyRemovesSelectedTrashedSessionAndRejectsTraversal() {
        val root = Files.createTempDirectory("purge-results").toFile()
        val saved = File(root, "1000").apply { mkdirs() }
        File(saved, "audio.wav").writeText("audio")
        val other = File(root, "2000").apply { mkdirs() }
        SessionLibrary.trash(root, "1000")
        assertThrows(IllegalArgumentException::class.java) { SessionLibrary.purge(root, "../2000") }
        SessionLibrary.purge(root, "1000")
        assertFalse(File(root, "trash/1000").exists())
        assertTrue(other.isDirectory)
    }

    @Test fun trashAndRestorePreserveAudioTextAndSourceAndHideDuplicateMarker() {
        val root = Files.createTempDirectory("sessions").toFile()
        val session = File(root, "1000").apply { mkdir() }
        File(session, "000000.wav").writeBytes(byteArrayOf(1, 2, 3))
        File(session, "source.txt").writeText("identity")
        File(session, "import-complete.txt").writeText("ok")
        SessionLibrary.rename(root, "1000", "  门店会议  ")
        assertEquals("门店会议", SessionLibrary.title(session))
        assertEquals("1000", SessionLibrary.imported(root, "identity"))
        SessionLibrary.trash(root, "1000")
        assertTrue(SessionLibrary.sessions(root).isEmpty())
        assertNull(SessionLibrary.imported(root, "identity"))
        SessionLibrary.restore(root, "1000")
        assertArrayEquals(byteArrayOf(1, 2, 3), File(session, "000000.wav").readBytes())
        assertEquals("门店会议", SessionLibrary.title(session))
        assertEquals("1000", SessionLibrary.imported(root, "identity"))
    }
    @Test fun traversalAndRestoreCollisionNeverOverwriteData() {
        val root = Files.createTempDirectory("sessions").toFile()
        for (id in listOf("../outside", "", "trash/123")) {
            try { SessionLibrary.trash(root, id); fail() } catch (_: IllegalArgumentException) { }
        }
        File(root, "1").mkdir()
        SessionLibrary.trash(root, "1")
        File(root, "1").mkdir()
        try { SessionLibrary.restore(root, "1"); fail() } catch (_: IllegalStateException) { }
        assertTrue(File(root, "trash/1").exists())
    }
    @Test fun duplicateIdentityIsStableAcrossPickerAndTreeUrisButChangesWithSource() {
        val a = SourceRecording("content://provider/tree/primary%3A/document/primary%3Afoo.wav", "20261112_130000_memo.wav", 1, 100)
        val b = a.copy(uri = "content://provider/document/primary%3Afoo.wav")
        assertEquals(a.identity, b.identity)
        assertNotEquals(a.identity, a.copy(size = 200).identity)
        assertTrue(a.recordedAt > 1)
    }
    @Test fun duplicateMarkerOpensNewestCompletedSession() {
        val root = Files.createTempDirectory("sessions").toFile()
        for (id in listOf("1000", "2000")) {
            val folder = File(root, id).apply { mkdir() }
            File(folder, "source.txt").writeText("identity")
            File(folder, "import-complete.txt").writeText("ok")
        }
        assertEquals("2000", SessionLibrary.imported(root, "identity"))
    }
    @Test fun incompleteImportsAreNotMarkedAsCompleteDuplicates() {
        val root = Files.createTempDirectory("sessions").toFile()
        val folder = File(root, "1").apply { mkdir() }
        File(folder, "source.txt").writeText("identity")
        assertNull(SessionLibrary.imported(root, "identity"))
    }
    @Test fun liveNetworkOverflowCannotBlockOrCorruptSavedRecording() {
        val queue = LiveAudioQueue(2)
        val folder = Files.createTempDirectory("live-capture").toFile()
        val buffer = ByteArray(8192) { (it % 127).toByte() }
        var total = 0
        SegmentWriter(folder, {}).use { writer ->
            repeat(500) { writer.write(buffer, buffer.size); total += buffer.size; queue.offer(buffer, buffer.size) }
        }
        assertTrue(queue.overflow)
        assertTrue(queue.finished)
        assertEquals(total.toLong(), RecordingStore.segments(folder).sumOf { it.length() - 44 })
    }
    @Test fun liveQueueCopiesCaptureBufferAndDrainsOnStop() {
        val queue = LiveAudioQueue(2)
        val bytes = byteArrayOf(1, 2)
        assertTrue(queue.offer(bytes, 2)); bytes[0] = 9
        queue.finish()
        assertArrayEquals(byteArrayOf(1, 2), queue.next())
        assertTrue(queue.drained())
        assertFalse(queue.offer(bytes, 2))
    }
    @Test fun updaterRejectsForeignRepositoriesPlaintextMissingHashesAndOversizedAssets() {
        val good = "https://github.com/sep1107/qisheng/releases/download/v0.6.6/qisheng-0.6.6.apk"
        val digest = "sha256:" + "a".repeat(64)
        UpdatePolicy.validateAsset(good, digest, 17000000)
        assertEquals(606, UpdatePolicy.versionCode("v0.6.6"))
        assertTrue(UpdatePolicy.versionCode("v1.0.0") > UpdatePolicy.versionCode("v0.99.99"))
        for (url in listOf(good.replace("https:", "http:"), good.replace("github.com", "github.com.evil.test"), good.replace("sep1107", "other"))) {
            try { UpdatePolicy.validateAsset(url, digest, 1); fail() } catch (_: IllegalArgumentException) { }
        }
        try { UpdatePolicy.validateAsset(good, "", 1); fail() } catch (_: IllegalArgumentException) { }
        try { UpdatePolicy.validateAsset(good, digest, 200000000); fail() } catch (_: IllegalArgumentException) { }
        try { UpdatePolicy.versionCode("v0.6.6-beta"); fail() } catch (_: IllegalStateException) { }
    }
}
