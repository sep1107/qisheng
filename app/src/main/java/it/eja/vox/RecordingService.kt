package it.eja.vox

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

data class RecordingStatus(
    val session: String = "", val recording: Boolean = false,
    val processing: Boolean = false, val seconds: Long = 0,
    val text: String = "", val message: String = "准备就绪"
)

object RecordingState {
    val status = mutableStateOf(RecordingStatus())
    private val handler = Handler(Looper.getMainLooper())
    fun update(change: (RecordingStatus) -> RecordingStatus) {
        handler.post { status.value = change(status.value) }
    }
}

class RecordingService : Service() {
    companion object {
        const val START = "record"
        const val STOP = "stop"
        const val RETRY = "retry"
    }
    @Volatile private var recording = false
    private var worker: Thread? = null
    private var session: File? = null
    private val uploads = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private val cloud by lazy {
        CloudApi(getSharedPreferences("vox_prefs", MODE_PRIVATE)) { message ->
            RecordingState.update { it.copy(message = message) }
        }
    }
    private var uploadFailed = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("recording", "录音与转写", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            recording = false
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY
        val root = File(filesDir, "recordings").apply { mkdirs() }
        val retry = intent?.action == RETRY
        val name = intent?.getStringExtra("session")
        if (retry && (name == null || !Regex("[0-9]+").matches(name) || !File(root, name).isDirectory)) {
            stopSelf(); return START_NOT_STICKY
        }
        session = File(root, if (retry) name!! else System.currentTimeMillis().toString()).apply { mkdirs() }
        recording = !retry
        ServiceCompat.startForeground(this, 1, notification(if (retry) "补传录音" else "正在录音"),
            when {
                retry && Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                Build.VERSION.SDK_INT >= 30 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            })
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vox:recording").apply { acquire(24 * 60 * 60 * 1000L) }
        RecordingState.update { RecordingStatus(session = session!!.name, recording = recording,
            processing = true, message = if (retry) "正在补传，成功分段不会重复上传" else "正在录音，每 2 分钟保存并转写") }
        worker = thread(name = "vox-capture") {
            val folder = session!!
            try {
                RecordingStore.recover(folder)
                if (retry) RecordingStore.pending(folder).forEach { enqueue(it) }
                else capture(folder)
            } catch (e: Exception) {
                RecordingStore.atomicText(File(folder, "capture-error.txt"), "录音中断：${e.javaClass.simpleName}")
                RecordingState.update { it.copy(message = "录音中断，已写入音频可在历史中恢复") }
            } finally {
                recording = false
                RecordingState.update { it.copy(recording = false) }
                uploads.execute {
                    val pending = RecordingStore.pending(folder).size
                    val captureError = File(folder, "capture-error.txt").exists()
                    RecordingState.update { it.copy(processing = false, text = RecordingStore.transcript(folder),
                        message = when {
                            captureError -> "录音曾中断，保留原音频；请检查历史记录"
                            pending > 0 -> "已保存，$pending 段待转写；联网并检查配置后点补传"
                            RecordingStore.segments(folder).isEmpty() -> "未收到有效音频，请检查麦克风"
                            else -> "已保存，全部分段转写完成"
                        }) }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun capture(folder: File) {
        val min = AudioRecord.getMinBufferSize(RecordingStore.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "不支持录音格式" }
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, RecordingStore.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
        val writer = SegmentWriter(folder, { enqueue(it) })
        var total = 0L
        var lastSecond = -1L
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            recorder.startRecording()
            val buffer = ByteArray(8192)
            while (recording) {
                check(folder.usableSpace > 16 * 1024 * 1024) { "存储空间不足" }
                val count = recorder.read(buffer, 0, buffer.size)
                check(count > 0) { "麦克风读取失败" }
                writer.write(buffer, count)
                total += count
                val second = total / RecordingStore.BYTES_PER_SECOND
                if (second != lastSecond) {
                    lastSecond = second
                    // Periodic fsync limits the tail exposed to an abrupt power loss.
                    writer.sync()
                    RecordingState.update { it.copy(seconds = second) }
                }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
            writer.close()
        }
    }

    private fun enqueue(audio: File) {
        uploads.execute {
            val textFile = File(audio.parentFile, audio.nameWithoutExtension + ".txt")
            if (textFile.exists() || uploadFailed) return@execute
            try {
                RecordingStore.atomicText(textFile, cloud.transcribe(audio))
                RecordingState.update { it.copy(text = RecordingStore.transcript(audio.parentFile!!)) }
            } catch (e: Exception) {
                uploadFailed = true
                // Keep audio and leave the segment pending; never persist keys or response bodies.
                RecordingState.update { it.copy(message = "部分分段待补传，录音继续保存在本机") }
            }
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "recording").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("柒声").setContentText(message).setContentIntent(open)
            .setOngoing(true).addAction(android.R.drawable.ic_media_pause, "停止录音", stop).build()
    }

    override fun onDestroy() {
        recording = false
        cloud.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        uploads.shutdown()
        super.onDestroy()
    }
}
