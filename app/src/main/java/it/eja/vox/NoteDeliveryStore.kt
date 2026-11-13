package it.eja.vox

import java.io.File
import java.io.IOException

/** A frozen request stays with its recording, including when moved to the recycle bin. */
object NoteDeliveryStore {
    private fun folder(session: File) = File(session, "delivery")
    fun status(session: File) = File(folder(session), "status.txt").takeIf { it.isFile }?.readText().orEmpty()
    @Synchronized fun status(session: File, text: String) {
        val dir = folder(session).apply { check(isDirectory || mkdirs()) }
        RecordingStore.atomicText(File(dir, "status.txt"), text)
    }
    fun ready(session: File) = File(folder(session), "ready").isFile
    fun accepted(session: File) = File(folder(session), "accepted").isFile
    fun eligible(session: File, since: Long): Boolean = session.isDirectory &&
        File(session, "summary.txt").let { it.isFile && it.lastModified() >= since && it.readText().isNotBlank() } &&
        RecordingStore.segments(session).isNotEmpty() && RecordingStore.pending(session).isEmpty() &&
        !File(session, "capture-error.txt").exists() && session.listFiles()?.none { it.extension == "part" } == true &&
        (!File(session, "import-incomplete.txt").exists() || File(session, "import-complete.txt").exists())

    @Synchronized
    fun prepare(session: File, destination: String, id: String, title: String, markdown: String): Boolean {
        val dir = folder(session).apply { check(isDirectory || mkdirs()) }
        if (ready(session)) {
            val same = read(session, "destination") == destination && read(session, "id") == id && read(session, "markdown") == markdown
            if (!same) status(session, "原发送版本已保留；内容或地址已变化，未自动覆盖，请手动核对。")
            return same && !accepted(session)
        }
        mapOf("destination" to destination, "id" to id, "title" to title, "markdown" to markdown).forEach { (key, value) ->
            RecordingStore.atomicText(File(dir, "$key.txt"), value)
        }
        RecordingStore.atomicText(File(dir, "ready"), "1")
        status(session, "自动发送已排队，等待联网和后台调度。")
        return true
    }
    fun read(session: File, key: String) = File(folder(session), "$key.txt").readText()
    @Synchronized fun accept(session: File, message: String) {
        RecordingStore.atomicText(File(folder(session), "accepted"), "1")
        val changed = read(session, "markdown") != RecordingExport.markdown(session)
        status(session, message + if (changed) "\n本机内容已更新，本次发送的是此前排队版本，未自动覆盖。" else "")
    }
    fun retryable(error: Exception) = error is IOException ||
        (error is ReceiverHttpError && (error.code == 429 || error.code >= 500))
}
