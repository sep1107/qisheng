package it.eja.vox

object ReceiverReceipt {
    fun message(code: Int, id: String, hash: String, receivedId: String, receivedHash: String, status: String): String {
        check(id == receivedId && hash == receivedHash) { "未收到匹配回执，无法确认保存；可安全重试，接收端应按 ID 去重" }
        return when {
            code == 202 && status == "queued" -> "接收端已排队，尚未确认写入 Obsidian"
            code in listOf(200, 201) && status in listOf("saved", "duplicate") -> "接收端已保存；手机是否收到取决于你的 Obsidian 同步"
            else -> error("接收回执状态无效，无法确认保存")
        }
    }
}
