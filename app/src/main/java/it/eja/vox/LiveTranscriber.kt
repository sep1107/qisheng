package it.eja.vox

import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Capture only offers a copy; a stalled network cannot block or grow capture memory. */
internal class LiveAudioQueue(private val capacity: Int = 32) {
    private val queue = ArrayBlockingQueue<ByteArray>(capacity)
    @Volatile var finished = false; private set
    @Volatile var overflow = false; private set
    fun offer(bytes: ByteArray, count: Int): Boolean {
        if (finished || overflow) return false
        if (!queue.offer(bytes.copyOf(count))) { overflow = true; finished = true; queue.clear(); return false }
        return true
    }
    fun finish() { finished = true }
    fun next(): ByteArray? = queue.poll(200, TimeUnit.MILLISECONDS)
    fun drained() = finished && queue.isEmpty()
}

class LiveTranscriber(private val prefs: SharedPreferences, private val text: (String) -> Unit,
                      private val status: (String) -> Unit) {
    private val queue = LiveAudioQueue()
    private val closed = AtomicBoolean(false)
    private var lastPreviewAt = 0L
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).followRedirects(false)
        .followSslRedirects(false).build()
    private val api = ParaformerApi(client)
    fun start() {
        thread(name = "qisheng-live") {
            try {
                val profiles = ApiProfiles.load(prefs, "asr").filter { it.model == "paraformer-realtime-v2" && it.url.startsWith("wss://") }
                ApiRouter(PreferenceQuota(prefs)).run(profiles, prefs.getBoolean("free_only", true), status) { profile ->
                    if (closed.get() || queue.overflow) throw ApiUnavailable("实时字幕已停止，录音继续")
                    api.stream(profile, "pcm", { socket, checkFailure ->
                        while (!queue.drained()) {
                            checkFailure()
                            if (closed.get() || queue.overflow) throw IOException("实时字幕缓冲已停止")
                            val bytes = queue.next() ?: continue
                            if (socket.queueSize() > 262144 || !socket.send(bytes.toByteString())) throw IOException("实时字幕网络过慢")
                        }
                        if (queue.overflow) throw IOException("实时字幕网络过慢")
                    }, {
                        val now = System.nanoTime()
                        if (!closed.get() && now - lastPreviewAt >= 200_000_000L) { lastPreviewAt = now; text(it) }
                    })
                }
                if (!closed.get()) status("实时字幕已结束，完整转写按分段保存")
            } catch (_: Exception) {
                if (!closed.get()) status("实时字幕暂不可用，录音与分段转写继续")
            } finally { queue.finish(); api.cancel(); client.dispatcher.cancelAll() }
        }
    }
    fun offer(bytes: ByteArray, count: Int) {
        if (!closed.get() && !queue.finished && !queue.offer(bytes, count)) {
            status("实时字幕网络过慢，已停止字幕；录音继续")
        }
    }
    fun finish() = queue.finish()
    fun cancel() { closed.set(true); queue.finish(); api.cancel(); client.dispatcher.cancelAll() }
}
