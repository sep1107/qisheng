package it.eja.vox

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Only finalized sentences are saved; partial revisions must not duplicate text. */
internal class ParaformerResults {
    private val sentences = sortedMapOf<Long, String>()
    @Synchronized fun update(begin: Long, text: String, final: Boolean) {
        if (final) sentences[begin] = text
    }
    @Synchronized fun text() = sentences.values.joinToString("")
}

class ParaformerApi(private val client: OkHttpClient) {
    private val active = ConcurrentHashMap.newKeySet<WebSocket>()

    fun transcribe(profile: ApiProfile, audio: File): String {
        if (profile.model != "paraformer-realtime-v2" || audio.extension.lowercase() != "wav") {
            throw ApiUnavailable("百炼直传目前支持 paraformer-realtime-v2 和本机 WAV 录音")
        }
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<IOException?>()
        val results = ParaformerResults()
        val id = UUID.randomUUID().toString()
        fun fail(error: IOException) { failure.compareAndSet(null, error); started.countDown(); finished.countDown() }
        fun command(action: String, payload: JSONObject) = JSONObject().put("header", JSONObject()
            .put("action", action).put("task_id", id).put("streaming", "duplex")).put("payload", payload).toString()
        val socket = client.newWebSocket(Request.Builder().url(profile.url)
            .header("Authorization", "Bearer ${profile.key}").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = JSONObject().put("task_group", "audio").put("task", "asr")
                    .put("function", "recognition").put("model", profile.model).put("input", JSONObject())
                    .put("parameters", JSONObject().put("format", "wav").put("sample_rate", 16000).put("heartbeat", true))
                if (!webSocket.send(command("run-task", payload))) fail(IOException("百炼连接已关闭"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val header = json.getJSONObject("header")
                    if (header.optString("task_id") != id) return
                    when (header.getString("event")) {
                        "task-started" -> started.countDown()
                        "result-generated" -> json.getJSONObject("payload").getJSONObject("output")
                            .optJSONObject("sentence")?.let { sentence ->
                                results.update(sentence.getLong("begin_time"), sentence.optString("text"),
                                    sentence.optBoolean("sentence_end"))
                            }
                        "task-finished" -> { finished.countDown(); webSocket.close(1000, null) }
                        "task-failed" -> {
                            // Never persist provider messages: they can contain request data.
                            val code = header.optString("error_code")
                            fail(when {
                                code.contains("FreeTierOnly") || code.contains("Quota", true) -> ApiHttpError(402)
                                code.contains("Throttl", true) || code.contains("Limit", true) -> ApiHttpError(429)
                                code.contains("Auth", true) || code.contains("Permission", true) -> ApiHttpError(403)
                                else -> ApiUnavailable("百炼转写任务失败，录音已保留，请检查模型权限与音频")
                            })
                            webSocket.cancel()
                        }
                    }
                } catch (_: org.json.JSONException) {
                    fail(ApiUnavailable("百炼响应格式错误，录音已保留")); webSocket.cancel()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(response?.takeIf { it.code >= 400 }?.let { ApiHttpError(it.code) } ?: IOException("百炼连接中断"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if (finished.count > 0) fail(IOException("百炼任务尚未完成，连接已关闭"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (finished.count > 0) fail(IOException("百炼任务尚未完成，连接已关闭"))
            }
        })
        active.add(socket)
        try {
            if (!started.await(30, TimeUnit.SECONDS)) throw IOException("百炼启动超时")
            failure.get()?.let { throw it }
            // Pace 100 ms frames so a recorded segment follows the documented streaming protocol.
            audio.inputStream().use { input ->
                val buffer = ByteArray(3200)
                while (true) {
                    failure.get()?.let { throw it }
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (!socket.send(buffer.toByteString(0, n))) throw IOException("百炼音频发送失败")
                    Thread.sleep(100)
                }
            }
            failure.get()?.let { throw it }
            if (!socket.send(command("finish-task", JSONObject().put("input", JSONObject())))) throw IOException("百炼连接已关闭")
            if (!finished.await(60, TimeUnit.SECONDS)) throw IOException("百炼结果等待超时")
            failure.get()?.let { throw it }
            return results.text()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ApiUnavailable("转写已取消，录音仍保存在本机")
        } finally {
            active.remove(socket)
            socket.cancel()
        }
    }

    fun cancel() = active.forEach { it.cancel() }
}
