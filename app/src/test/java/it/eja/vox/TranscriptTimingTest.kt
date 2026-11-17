package it.eja.vox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TranscriptTimingTest {
    @Test fun offsetsAndSpeakerNumbersStayWithinTheirOwnSegments() {
        assertEquals(240000L, TranscriptTiming.offset(File("000002.wav")))
        assertEquals("[01:01:01]", TranscriptTiming.stamp(3661000))
        assertEquals("[00:04:03] 分段3·说话人1：明天开会", TranscriptTiming.line(240000, 3.2, "明天开会", "1", "3"))
        assertEquals("[00:02:03] 分段2·说话人1：讨论方案", TranscriptTiming.line(120000, 3.2, "讨论方案", "1", "2"))
        assertEquals("[00:00:03] 普通转写", TranscriptTiming.line(0, 3.2, "普通转写"))
    }
    @Test fun missingTimingIsNotInventedAndBlankResultsStayBlank() {
        assertEquals("未提供时间", TranscriptTiming.line(120000, Double.NaN, "未提供时间"))
        assertEquals("", TranscriptTiming.line(120000, 2.0, " ", "1", "2"))
        assertEquals("[00:00:00] 原文", TranscriptTiming.line(0, 0.0, "原文", "姓名\n伪造", "1"))
    }
    @Test fun paraformerKeepsFinalSentenceTimesAndTranscriptDoesNotDoubleStamp() {
        val results = ParaformerResults(timestampOffsetMs = 120000)
        results.update(2500, "下一句", true)
        results.update(0, "第一句", true)
        results.update(2500, "下一句修订", true)
        results.update(6000, " ", true)
        assertEquals("[00:02:00] 第一句\n[00:02:02] 下一句修订", results.text())
        val session = Files.createTempDirectory("timed-transcript").toFile()
        File(session, "000001.wav").writeBytes(byteArrayOf(1))
        File(session, "000001.txt").writeText(results.text())
        assertEquals(results.text(), RecordingStore.transcript(session))
        assertEquals("", ParaformerResults(timestampOffsetMs = 0).text())
    }
}
