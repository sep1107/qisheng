package it.eja.vox

import org.junit.Assert.assertEquals
import org.junit.Test

class ParaformerResultsTest {
    @Test fun partialRevisionsAreIgnoredAndFinalSentencesStayOrderedWithoutDuplicates() {
        val results = ParaformerResults()
        results.update(1000, "明", false)
        results.update(1000, "明天开会。", true)
        results.update(0, "今天录音。", true)
        results.update(1000, "明天开会。", true)
        results.update(2000, "尚未确认的尾句", false)
        assertEquals("今天录音。明天开会。", results.text())
    }
}
