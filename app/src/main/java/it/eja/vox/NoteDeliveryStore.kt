package it.eja.vox

import java.io.File
import java.io.IOException

/** Each content version has an immutable payload and its own receipt. Legacy queues remain readable. */
object NoteDeliveryStore {
    fun revision(session: File) = File(session, "delivery/current.txt").takeIf { it.isFile }?.readText().orEmpty()
    private fun folder(session: File, revision: String): File {
        require(revision.isEmpty() || revision.matches(Regex("[a-f0-9]{64}")))
        return File(session, if (revision.isEmpty()) "delivery" else "delivery/versions/$revision")
    }
    fun status(session: File): String {
        val revision = revision(session)
        if (ready(session, revision) && read(session, "markdown", revision) != RecordingExport.markdown(session)) {
            return if (RecordingStore.savedSummary(session).isNotBlank() && !RecordingStore.summaryCurrent(session))
                "转写内容已更新，旧总结尚未更新；待补齐并更新总结，新版尚未发送。"
            else "内容已更新，更新版尚未发送。"
        }
        return File(folder(session, revision), "status.txt").takeIf { it.isFile }?.readText().orEmpty()
    }
    @Synchronized fun status(session: File, text: String, revision: String = revision(session)) {
        val dir = folder(session, revision).apply { check(isDirectory || mkdirs()) }
        RecordingStore.atomicText(File(dir, "status.txt"), text)
    }
    fun ready(session: File, revision: String = revision(session)) = File(folder(session, revision), "ready").isFile
    fun accepted(session: File, revision: String = revision(session)) = File(folder(session, revision), "accepted").isFile
    fun eligible(session: File, since: Long): Boolean = session.isDirectory &&
        File(session, "summary.txt").let { it.isFile && it.lastModified() >= since && it.readText().isNotBlank() } &&
        RecordingStore.summaryCurrent(session) && RecordingStore.segments(session).isNotEmpty() && RecordingStore.pending(session).isEmpty() &&
        !File(session, "capture-error.txt").exists() && session.listFiles()?.none { it.extension == "part" } == true &&
        (!File(session, "import-incomplete.txt").exists() || File(session, "import-complete.txt").exists())

    @Synchronized
    fun prepare(session: File, destination: String, id: String, title: String, markdown: String,
                allowDestinationChange: Boolean = false): Boolean {
        val current = revision(session)
        if (ready(session, current)) {
            val sameDestination = read(session, "destination", current) == destination
            if (sameDestination && read(session, "markdown", current) == markdown) return !accepted(session, current)
            if (!sameDestination && !allowDestinationChange) {
                status(session, "接收地址已变化，请检查设置后手动发送。", current)
                return false
            }
        }
        val version = RecordingExport.sha256("$id\n$destination\n$markdown")
        val dir = folder(session, version).apply { check(isDirectory || mkdirs()) }
        if (!ready(session, version)) {
            // A content-derived ID works with existing schema-1 receivers without overwriting older notes.
            mapOf("destination" to destination, "id" to "r${version.take(40)}", "title" to title, "markdown" to markdown).forEach { (key, value) ->
                RecordingStore.atomicText(File(dir, "$key.txt"), value)
            }
            RecordingStore.atomicText(File(dir, "ready"), "1")
            status(session, "发送已排队，等待联网和后台调度。", version)
        }
        // Publish the pointer only after the frozen payload is complete.
        RecordingStore.atomicText(File(session, "delivery/current.txt"), version)
        return !accepted(session, version)
    }
    fun read(session: File, key: String, revision: String = revision(session)) = File(folder(session, revision), "$key.txt").readText()
    @Synchronized fun accept(session: File, message: String, revision: String = revision(session)) {
        status(session, message, revision)
        RecordingStore.atomicText(File(folder(session, revision), "accepted"), "1")
    }
    fun retryable(error: Exception) = error is IOException ||
        (error is ReceiverHttpError && (error.code == 429 || error.code >= 500))
}
