package it.eja.vox

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class NoteDeliveryStoreTest {
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

    @Test fun queuedPayloadSurvivesRetryAndRejectsSilentContentOrDestinationReplacement() {
        val session = session()
        val body = RecordingExport.markdown(session)
        assertTrue(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", body))
        assertTrue(NoteDeliveryStore.ready(session))
        assertTrue(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", body))
        assertFalse(NoteDeliveryStore.prepare(session, "https://two.example/v1/notes", "device-session", "会议", body))
        assertEquals("https://one.example/v1/notes", NoteDeliveryStore.read(session, "destination"))
        File(session, "summary.txt").writeText("后来重新生成的总结")
        assertFalse(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", RecordingExport.markdown(session)))
        assertEquals(body, NoteDeliveryStore.read(session, "markdown"))
        NoteDeliveryStore.accept(session, "发送成功")
        assertTrue(NoteDeliveryStore.accepted(session))
        assertTrue(NoteDeliveryStore.status(session).contains("此前排队版本"))
        assertFalse(NoteDeliveryStore.prepare(session, "https://one.example/v1/notes", "device-session", "会议", body))
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
}
