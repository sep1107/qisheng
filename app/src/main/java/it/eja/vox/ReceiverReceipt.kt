package it.eja.vox

class ReceiverHttpError(val code: Int) : IllegalStateException(
    if (code == 409) "接收端已有同一录音的不同内容，未覆盖，请到接收端核对" else "接收失败（HTTP $code），录音和文字仍保存在本机")

object ReceiverReceipt {
    fun message(code: Int, id: String, hash: String, receivedId: String, receivedHash: String, status: String): String {
        check(id == receivedId && hash == receivedHash) { "未收到匹配回执，无法确认保存；可安全重试，接收端应按 ID 去重" }
        return when {
            code == 202 && status == "queued" -> "发送成功：接收端已排队，尚未确认写入 Obsidian"
            code in listOf(200, 201) && status in listOf("saved", "duplicate") -> "发送成功：接收端已保存；手机是否收到取决于你的 Obsidian 同步"
            else -> error("接收回执状态无效，无法确认保存")
        }
    }
}
