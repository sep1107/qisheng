package it.eja.vox

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class NoteDeliveryStoreTest {
    @Test fun versionIdsMatchTheSharedReceiverProtocolFixture() {
        val session = session()
        javaClass.getResourceAsStream("/delivery-revisions.tsv")!!.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val fields = line.split('\t')
                val title = String(java.util.Base64.getDecoder().decode(fields[2]), Charsets.UTF_8)
                val markdown = String(java.util.Base64.getDecoder().decode(fields[3]), Charsets.UTF_8)
                assertTrue(NoteDeliveryStore.prepare(session, fields[1], fields[0], title, markdown))
                assertEquals(fields[4], NoteDeliveryStore.read(session, "id"))
                assertEquals(fields[5], RecordingExport.sha256(NoteDeliveryStore.read(session, "markdown")))
            }
        }
    }

    private fun session(): File = File(Files.createTempDirectory("note-delivery").toFile(), "1234567890000").apply {
        mkdirs()
        File(this, "000000.wav").writeBytes(byteArrayOf(1, 2))
        File(this, "000000.txt").writeText("今天下午三点开会。")
        File(this, "summary.txt").writeText("会议时间：下午三点。")
    }

    @Test fun oldSummariesPartialImportsAndIncompleteSegmentsAreNotAutomaticallySent() {
        val complete = session()
        assertTrue(NoteDeliveryStore.eligible(complete, 0))
        assertFalse(NoteDeliveryStore.eligible(complete, System.currentTimeMillis() + 10000))
        val incomplete = session()
        File(incomplete, "000001.wav").writeBytes(byteArrayOf(3, 4))
        assertFalse(NoteDeliveryStore.eligible(incomplete, 0))
        val importing = session()
        File(importing, "import-incomplete.txt").writeText("not complete")
        assertFalse(NoteDeliveryStore.eligible(importing, 0))
        File(importing, "import-complete.txt").writeText("complete")
        assertTrue(NoteDeliveryStore.eligible(importing, 0))
        val recording = session()
        File(recording, "000001.part").writeBytes(byteArrayOf(1))
        assertFalse(NoteDeliveryStore.eligible(recording, 0))
        val failed = session()
        File(failed, "capture-error.txt").writeText("interrupted")
        assertFalse(NoteDeliveryStore.eligible(failed, 0))
    }

    @Test fun contentUpdateHidesOldSuccessAndLateReceiptCannotAcceptTheNewVersion() {
        val session = session()
        val first = RecordingExport.markdown(session)
        assertTrue(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", first))
        val old = NoteDeliveryStore.revision(session)
        val oldId = NoteDeliveryStore.read(session, "id", old)
        NoteDeliveryStore.accept(session, "发送成功", old)
        assertEquals("发送成功", NoteDeliveryStore.status(session))
        File(session, "000001.wav").writeBytes(byteArrayOf(3, 4))
        File(session, "000001.txt").writeText("补齐的重要结论。")
        assertFalse(NoteDeliveryStore.status(session).contains("发送成功"))
        RecordingStore.summary(session, true) { "包含补齐内容的新总结" }
        val next = RecordingExport.markdown(session)
        assertTrue(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", next))
        val current = NoteDeliveryStore.revision(session)
        assertNotEquals(old, current)
        assertNotEquals(oldId, NoteDeliveryStore.read(session, "id"))
        assertEquals(first, NoteDeliveryStore.read(session, "markdown", old))
        NoteDeliveryStore.accept(session, "发送成功", old) // A delayed response for the older request.
        assertFalse(NoteDeliveryStore.accepted(session))
        assertFalse(NoteDeliveryStore.status(session).contains("发送成功"))
        assertTrue(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", next))
        assertEquals(current, NoteDeliveryStore.revision(session)) // Offline retries use the same ID and payload.
        NoteDeliveryStore.accept(session, "发送成功", current)
        assertEquals("发送成功", NoteDeliveryStore.status(session))
        assertFalse(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", next))
    }

    @Test fun legacySuccessIsReadableButCannotHideNewTranscription() {
        val session = session()
        val legacy = File(session, "delivery").apply { mkdirs() }
        mapOf("id" to "legacy-id", "destination" to "https://one.example/v1/notes", "markdown" to RecordingExport.markdown(session),
            "title" to "会议", "ready" to "1", "accepted" to "1", "status" to "发送成功").forEach { (key, value) ->
            File(legacy, if (key in listOf("ready", "accepted")) key else "$key.txt").writeText(value)
        }
        assertEquals("", NoteDeliveryStore.revision(session))
        assertEquals("发送成功", NoteDeliveryStore.status(session))
        File(session, "000000.txt").writeText("更新的转写内容")
        assertFalse(NoteDeliveryStore.status(session).contains("发送成功"))
        RecordingStore.summary(session, true) { "更新总结" }
        assertTrue(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", RecordingExport.markdown(session)))
        assertFalse(NoteDeliveryStore.accepted(session))
        assertEquals("1", File(legacy, "accepted").readText())
    }

    @Test fun automaticDestinationChangeIsBlockedButExplicitManualSendCanChangeIt() {
        val session = session()
        val body = RecordingExport.markdown(session)
        NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", body)
        assertFalse(NoteDeliveryStore.prepare(session, "https://two.example/v1/notes", "device-session", "会议", body))
        assertEquals("https://one.example/v1/notes", NoteDeliveryStore.read(session, "destination"))
        assertTrue(NoteDeliveryStore.prepare(session, "https://two.example/v1/notes", "device-session", "会议", body, true))
        assertEquals("https://two.example/v1/notes", NoteDeliveryStore.read(session, "destination"))
    }

    @Test fun movingRecordingToTrashAlsoMovesItsPendingPayloadAndRemovesItFromActiveSessions() {
        val session = session()
        NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", RecordingExport.markdown(session))
        SessionLibrary.trash(session.parentFile, session.name)
        assertFalse(session.exists())
        assertTrue(SessionLibrary.sessions(session.parentFile).isEmpty())
        assertTrue(NoteDeliveryStore.ready(File(session.parentFile, "trash/${session.name}")))
    }

    @Test fun onlyNetworkAndTemporaryHttpFailuresRetry() {
        assertTrue(NoteDeliveryStore.retryable(IOException("offline")))
        listOf(429, 500, 503).forEach { assertTrue(NoteDeliveryStore.retryable(ReceiverHttpError(it))) }
        listOf(400, 401, 403, 404, 409, 413).forEach { assertFalse(NoteDeliveryStore.retryable(ReceiverHttpError(it))) }
        assertFalse(NoteDeliveryStore.retryable(IllegalStateException("unmatched receipt")))
    }

    @Test fun simultaneousStatusWritersKeepOneCompleteResultWithoutLostStagingFiles() {
        val session = session()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = (0..1).map { writer -> executor.submit {
                repeat(12) { NoteDeliveryStore.status(session, "发送状态-$writer-$it") }
            } }
            futures.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(NoteDeliveryStore.status(session) in setOf("发送状态-0-11", "发送状态-1-11"))
        } finally { executor.shutdownNow() }
    }
}
