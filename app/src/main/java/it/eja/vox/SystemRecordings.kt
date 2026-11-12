package it.eja.vox

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

object SystemRecordings {
    fun scan(context: Context, tree: Uri): List<SourceRecording> {
        val result = mutableListOf<SourceRecording>()
        val visited = mutableSetOf<String>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        fun walk(id: String, depth: Int) {
            check(depth <= 12 && visited.size < 20000) { "目录太大，请选择具体录音目录" }
            if (!visited.add(id)) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")
            val cursor = context.contentResolver.query(children, columns, null, null, null)
                ?: error("无法读取录音目录，请重新授权")
            cursor.use {
                while (it.moveToNext()) {
                    val child = it.getString(0); val name = it.getString(1) ?: ""
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // At a volume root inspect native month folders, not unrelated app or music trees.
                        val volumeRoot = depth == 0 && root.endsWith(":")
                        if (!volumeRoot || Regex("[0-9]{4}\\.[0-9]{2}").matches(name) ||
                            name.lowercase() in listOf("recordings", "recorder", "sounds", "record")) walk(child, depth + 1)
                    } else if (name.substringAfterLast('.', "").lowercase() in listOf("wav", "mp3", "m4a", "aac", "amr", "ogg", "flac")) {
                        check(result.size < 20000) { "录音过多，请选择具体目录" }
                        result.add(SourceRecording(DocumentsContract.buildDocumentUriUsingTree(tree, child).toString(), name,
                            it.getLong(4), it.getLong(3)))
                    }
                }
            }
        }
        walk(root, 0)
        return result.sortedWith(compareByDescending<SourceRecording> { it.recordedAt }.thenByDescending { it.name })
    }
}
