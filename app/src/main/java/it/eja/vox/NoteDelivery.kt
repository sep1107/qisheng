package it.eja.vox

import android.content.Context
import android.content.SharedPreferences
import androidx.work.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object NoteDelivery {
    private const val TAG = "qisheng-auto-send"
    @Synchronized fun deviceId(prefs: SharedPreferences): String {
        prefs.getString("export_device_id", "").orEmpty().takeIf { it.isNotEmpty() }?.let { return it }
        val id = UUID.randomUUID().toString()
        check(prefs.edit().putString("export_device_id", id).commit()) { "无法保存发送标识，尚未发送" }
        return id
    }
    fun queue(context: Context, session: File) {
        val prefs = context.getSharedPreferences("vox_prefs", 0)
        if (!prefs.getBoolean("receiver_enabled", false) || !prefs.getBoolean("receiver_auto_send", false)) return
        try {
            if (!NoteDeliveryStore.eligible(session, prefs.getLong("receiver_auto_since", Long.MAX_VALUE))) return
            val destination = RecordingExport.receiverUrl(prefs.getString("receiver_url", "").orEmpty())
            if (NoteDeliveryStore.prepare(session, destination, "${deviceId(prefs)}-${session.name}",
                    SessionLibrary.title(session).ifBlank { "柒声录音" }, RecordingExport.markdown(session))) schedule(context, session)
        } catch (_: Exception) {
            runCatching { NoteDeliveryStore.status(session, "自动发送未能排队，总结仍保留；请检查接收设置或手动发送。") }
        }
    }
    private fun schedule(context: Context, session: File) {
        val request = OneTimeWorkRequestBuilder<NoteDeliveryWorker>()
            .setInputData(workDataOf("session" to session.name))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build()
        WorkManager.getInstance(context).enqueueUniqueWork("$TAG-${session.name}", ExistingWorkPolicy.KEEP, request)
    }
    fun resume(context: Context) {
        val prefs = context.getSharedPreferences("vox_prefs", 0)
        if (!prefs.getBoolean("receiver_enabled", false) || !prefs.getBoolean("receiver_auto_send", false)) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            SessionLibrary.sessions(File(context.filesDir, "recordings")).forEach { session ->
                if (NoteDeliveryStore.ready(session) && !NoteDeliveryStore.accepted(session)) {
                    runCatching { NoteDeliveryStore.status(session, "自动发送已关闭，文字仍保留。") }
                }
            }
            return
        }
        SessionLibrary.sessions(File(context.filesDir, "recordings")).forEach { session ->
            if (NoteDeliveryStore.ready(session) && !NoteDeliveryStore.accepted(session)) schedule(context, session)
            else if (!NoteDeliveryStore.ready(session)) queue(context, session)
        }
    }
}

class NoteDeliveryWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val id = inputData.getString("session") ?: return Result.failure()
        if (!Regex("[0-9]+").matches(id)) return Result.failure()
        val session = File(applicationContext.filesDir, "recordings/$id")
        if (!session.isDirectory || !NoteDeliveryStore.ready(session)) return Result.success()
        if (NoteDeliveryStore.accepted(session)) return Result.success()
        val prefs = applicationContext.getSharedPreferences("vox_prefs", 0)
        if (!prefs.getBoolean("receiver_auto_send", false) || !prefs.getBoolean("receiver_enabled", false)) {
            NoteDeliveryStore.status(session, "自动发送已关闭，文字仍保留。")
            return Result.success()
        }
        if (RecordingService.busy) {
            NoteDeliveryStore.status(session, "自动发送等待录音或转写结束，文字仍保留。")
            return Result.retry()
        }
        return try {
            val destination = NoteDeliveryStore.read(session, "destination")
            check(destination == RecordingExport.receiverUrl(prefs.getString("receiver_url", "").orEmpty())) { "接收地址已改变，未发送旧队列内容，请手动核对。" }
            NoteDeliveryStore.status(session, "正在自动发送文字，等待接收端确认…")
            val message = NoteReceiver.send(destination, prefs.getString("receiver_token", "").orEmpty(),
                NoteDeliveryStore.read(session, "id"), NoteDeliveryStore.read(session, "title"), NoteDeliveryStore.read(session, "markdown"))
            NoteDeliveryStore.accept(session, message)
            Result.success()
        } catch (error: Exception) {
            val retry = NoteDeliveryStore.retryable(error)
            runCatching { NoteDeliveryStore.status(session,
                if (retry) "自动发送未确认，将在联网后退避重试；文字仍保留。"
                else "自动发送未完成，请检查设置后重试。${if (error is IllegalStateException || error is IllegalArgumentException) error.message.orEmpty() else ""}") }
            if (retry) Result.retry() else Result.failure()
        }
    }
}
