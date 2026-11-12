package it.eja.vox

object RecordingSummary {
    fun generate(cloud: CloudApi, text: String, language: String): String {
        RecordingStore.requireTranscript(text)
        val instruction = "总结录音：保留重要事实、数字、决定和待办，不编造。输出语言：${language.ifBlank { "中文" }}。"
        fun request(part: String) = cloud.chat("$instruction\n\n$part")
        var parts = RecordingStore.promptChunks(text)
        var rounds = 0
        while (parts.size > 1) {
            check(rounds++ < 8) { "总结过长，请稍后重试" }
            parts = RecordingStore.promptChunks(parts.map { request(it) }.joinToString("\n\n"))
        }
        return request(parts.singleOrNull() ?: "")
    }
}
