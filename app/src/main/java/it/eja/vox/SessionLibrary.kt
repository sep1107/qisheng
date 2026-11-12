package it.eja.vox

import java.io.File

object SessionLibrary {
    private fun folder(root: File, id: String): File {
        require(Regex("[0-9]+").matches(id))
        val result = File(root, id)
        require(result.canonicalFile.parentFile == root.canonicalFile)
        return result
    }
    fun sessions(root: File) = root.listFiles()?.filter {
        it.isDirectory && it.canonicalFile == File(root.canonicalFile, it.name) && Regex("[0-9]+").matches(it.name)
    }?.sortedByDescending { it.name.toLongOrNull() ?: 0 } ?: emptyList()
    fun title(session: File): String = File(session, "name.txt").takeIf { it.isFile }?.readText()?.trim().orEmpty()
    fun rename(root: File, id: String, name: String) {
        require(name.length <= 100)
        val session = folder(root, id); require(session.isDirectory)
        RecordingStore.atomicText(File(session, "name.txt"), name.trim())
    }
    fun trash(root: File, id: String) {
        val source = folder(root, id); require(source.isDirectory)
        val bin = File(root, "trash").apply { check(isDirectory || mkdirs()) }
        val destination = folder(bin, id)
        check(!destination.exists() && source.renameTo(destination)) { "无法移入回收站" }
    }
    fun restore(root: File, id: String) {
        val source = folder(File(root, "trash"), id)
        val destination = folder(root, id)
        check(source.isDirectory && !destination.exists() && source.renameTo(destination)) { "无法恢复" }
    }
    fun purge(root: File, id: String) {
        val target = folder(File(root, "trash"), id)
        require(target.isDirectory)
        fun remove(file: File) {
            require(file.canonicalPath.startsWith(target.canonicalPath + File.separator) || file == target)
            if (file.isDirectory) file.listFiles()?.forEach(::remove)
            check(file.delete()) { "清除失败，请重试" }
        }
        remove(target)
    }
    fun importIndex(root: File): Map<String, String> = buildMap {
        sessions(root).forEach { folder ->
            val source = File(folder, "source.txt")
            if (source.isFile && File(folder, "import-complete.txt").isFile) {
                val identity = source.readText()
                if (!containsKey(identity)) put(identity, folder.name)
            }
        }
    }
    fun imported(root: File, identity: String): String? = importIndex(root)[identity]
}

data class SourceRecording(val uri: String, val name: String, val modified: Long, val size: Long) {
    // A changed file must not be mistaken for the already imported version.
    val identity get() = uri.replace(Regex("/tree/[^/]+(?=/document/)"), "") + "\n$size\n$modified"
    val recordedAt: Long get() {
        val stamp = Regex("([0-9]{8}_[0-9]{6})").find(name)?.value ?: return modified
        return runCatching { java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
            .apply { isLenient = false }.parse(stamp)!!.time }.getOrDefault(modified)
    }
}
