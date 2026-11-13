package it.eja.vox

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object NoteReceiver {
    fun send(url: String, token: String, id: String, title: String, markdown: String): String {
        require(token.length >= 24 && token.all { it.code in 33..126 }) { "接收令牌至少24位，且不能包含空格、换行或非ASCII字符" }
        val hash = RecordingExport.sha256(markdown)
        val body = JSONObject().put("schema", 1).put("id", id).put("title", title)
            .put("markdown", markdown).put("sha256", hash).toString()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(RecordingExport.receiverUrl(url)).header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (response.code !in listOf(200, 201, 202)) throw ReceiverHttpError(response.code)
            val bytes = response.body?.byteStream()?.use { input ->
                val result = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    check(result.size() + n <= 65536) { "接收回执过大" }; result.write(buffer, 0, n)
                }
                result.toString("UTF-8")
            }.orEmpty()
            val ack = runCatching { JSONObject(bytes) }.getOrNull()
            check(ack != null) { "接收端未提供有效 JSON 回执，无法确认保存" }
            return ReceiverReceipt.message(response.code, id, hash, ack.optString("id"), ack.optString("sha256"), ack.optString("status"))
        }
    }
}
