package it.eja.vox

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordingStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedSegmentGetsValidHeaderAndPendingAudioIsRetained() {
        val session = temporary.newFolder("session")
        val part = File(session, "000000.part")
        part.writeBytes(ByteArray(44 + 32001) { 17 })
        RecordingStore.recover(session)
        val wav = RecordingStore.segments(session).single()
        val data = wav.readBytes()
        val header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(data, 0, 4))
        assertEquals(32000, header.getInt(40))
        assertEquals(32044L, wav.length())
        assertEquals(1, RecordingStore.pending(session).size)
        RecordingStore.atomicText(File(session, "000000.txt"), "你好")
        assertTrue(RecordingStore.pending(session).isEmpty())
        assertTrue(wav.exists())
    }

    @Test fun partialResultsRemainOrderedWithoutPretendingMissingSegmentSucceeded() {
        val session = temporary.newFolder("ordered")
        listOf("000002", "000000", "000001").forEach {
            File(session, "$it.wav").writeBytes(ByteArray(46))
        }
        RecordingStore.atomicText(File(session, "000002.txt"), "第三段")
        RecordingStore.atomicText(File(session, "000000.txt"), "第一段")
        assertEquals(listOf("000001.wav"), RecordingStore.pending(session).map { it.name })
        assertEquals("[0:00]\n第一段\n\n[4:00]\n第三段", RecordingStore.transcript(session))
    }

    @Test fun twoMinuteChunksStaySmallAndLongSummaryDoesNotDropInput() {
        assertEquals(3840000, RecordingStore.SEGMENT_BYTES)
        val input = "会议内容0123456789".repeat(4000)
        val chunks = RecordingStore.promptChunks(input)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 12000 })
        assertEquals(input, chunks.joinToString(""))
    }

    @Test fun captureBuffersCrossSegmentsWithoutLostOrDuplicatedSamples() {
        val folder = temporary.newFolder("capture")
        val ready = mutableListOf<File>()
        val input = ByteArray(22000) { (it % 127).toByte() }
        SegmentWriter(folder, { ready.add(it) }, 8000).use { writer ->
            input.asList().chunked(4096).forEach { chunk -> writer.write(chunk.toByteArray(), chunk.size) }
        }
        assertEquals(listOf(8044L, 8044L, 6044L), ready.map { it.length() })
        val recovered = ready.flatMap { it.readBytes().drop(44) }.toByteArray()
        assertArrayEquals(input, recovered)
    }

    @Test fun sixteenMinutesCrossesOldLimitWithSmallIndependentFiles() {
        val folder = temporary.newFolder("long")
        val ready = mutableListOf<File>()
        val buffer = ByteArray(8192) { 37 }
        val total = RecordingStore.BYTES_PER_SECOND * (16 * 60 + 1)
        var remaining = total
        SegmentWriter(folder, { ready.add(it) }).use { writer ->
            while (remaining > 0) {
                val count = minOf(buffer.size, remaining)
                writer.write(buffer, count)
                remaining -= count
            }
        }
        assertEquals(9, ready.size)
        assertTrue(ready.take(8).all { it.length() == RecordingStore.SEGMENT_BYTES + 44L })
        assertEquals(RecordingStore.BYTES_PER_SECOND + 44L, ready.last().length())
        assertEquals(total.toLong(), ready.sumOf { it.length() - 44 })
    }
}
