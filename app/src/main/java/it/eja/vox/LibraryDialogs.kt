package it.eja.vox

import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private fun date(time: Long) = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(time))

@Composable
fun HistoryDialog(root: File, busy: Boolean, close: () -> Unit, select: (File) -> Unit, trashed: (String) -> Unit) {
    var bin by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<File?>(null) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<File?>(null) }
    var purging by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf("") }
    val entries by produceState(emptyList<Pair<File, String>>(), revision, bin) {
        value = withContext(Dispatchers.IO) { SessionLibrary.sessions(if (bin) File(root, "trash") else root).map { it to SessionLibrary.title(it) } }
    }
    fun change(action: () -> Unit) { runCatching { check(!RecordingService.busy); action() }.onSuccess { revision++ }.onFailure { error = "操作失败，记录仍保留，请重试" } }
    AlertDialog(onDismissRequest = close, title = { Text(if (bin) "录音回收站" else "录音历史") }, text = {
        Column {
            Row { TextButton({ bin = !bin }) { Text(if (bin) "返回历史" else "回收站") } }
            if (error.isNotEmpty()) Text(error)
            if (bin) {
                Text("彻底清除后不可恢复；系统原录音不受影响")
                TextButton({ purging = entries.map { it.first.name } }, enabled = !busy && entries.isNotEmpty()) { Text("一键清空") }
            }
            if (entries.isEmpty()) Text("暂无记录")
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(entries, key = { it.first.name }) { (folder, title) ->
                    Column {
                        TextButton({ if (!busy && !bin) select(folder) }, enabled = !busy && !bin) {
                            Column { Text(title.ifBlank { "未命名录音" }); Text(date(folder.name.toLong()) + " · 待转写 ${RecordingStore.pending(folder).size} 段") }
                        }
                        Row {
                            if (bin) {
                                TextButton({ change { SessionLibrary.restore(root, folder.name) } }, enabled = !busy) { Text("恢复") }
                                TextButton({ purging = listOf(folder.name) }, enabled = !busy) { Text("彻底清除") }
                            }
                            else {
                                TextButton({ name = title; editing = folder }, enabled = !busy) { Text("备注名") }
                                TextButton({ deleting = folder }, enabled = !busy) { Text("删除") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
    purging?.let { ids ->
        AlertDialog(onDismissRequest = { purging = null }, title = { Text("彻底清除 ${ids.size} 条录音？") },
            text = { Text("将永久清除柒声回收站中的音频、转写和总结，不可恢复。系统录音机原文件不受影响。") },
            confirmButton = { TextButton({ if (!busy) change { ids.forEach { SessionLibrary.purge(root, it) } }; purging = null }) { Text("确认清除") } },
            dismissButton = { TextButton({ purging = null }) { Text("取消") } })
    }
    editing?.let { folder ->
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("录音备注名") },
            text = { TextField(name, { if (it.length <= 100) name = it }, label = { Text("名称（可留空）") }) },
            confirmButton = { TextButton({ if (!busy) change { SessionLibrary.rename(root, folder.name, name) }; editing = null }) { Text("保存") } },
            dismissButton = { TextButton({ editing = null }) { Text("取消") } })
    }
    deleting?.let { folder ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移入回收站？") },
            text = { Text("柒声内的音频、转写和总结将移入回收站，可恢复。系统录音机的原始文件不会删除。") },
            confirmButton = { TextButton({ if (!busy) change { SessionLibrary.trash(root, folder.name); trashed(folder.name) }; deleting = null }) { Text("移入回收站") } },
            dismissButton = { TextButton({ deleting = null }) { Text("取消") } })
    }
}

@Composable
fun SystemRecordingsDialog(prefs: SharedPreferences, root: File, busy: Boolean, chooseFolder: () -> Unit,
                           chooseFile: () -> Unit, close: () -> Unit, import: (SourceRecording, Boolean) -> Unit, open: (File) -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<SourceRecording?>(null) }
    var autoSummary by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val tree = prefs.getString("recording_tree", "").orEmpty()
    val sources by produceState(emptyList<Pair<SourceRecording, String?>>(), tree, revision) {
        loading = true; error = ""
        value = withContext(Dispatchers.IO) {
            try {
                if (tree.isBlank()) emptyList() else {
                    val imported = SessionLibrary.importIndex(root)
                    SystemRecordings.scan(context, Uri.parse(tree)).map { it to imported[it.identity] }
                }
            } catch (_: Exception) { error = "无法读取目录，请重新授权录音目录"; emptyList() }
        }
        loading = false
    }
    AlertDialog(onDismissRequest = close, title = { Text("系统录音 · 最新在前") }, text = {
        Column {
            Text("首次授权内部存储，可自动查找搜狗年月录音目录；也可授权具体录音文件夹。")
            Row { TextButton(chooseFolder, enabled = !busy) { Text(if (tree.isBlank()) "授权录音目录" else "更换目录") }; TextButton({ revision++ }) { Text("刷新") } }
            TextButton(chooseFile, enabled = !busy) { Text("手动选文件") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotEmpty()) Text(error)
            if (!loading && sources.isEmpty()) Text("未找到录音；可刷新、更换目录或手动选文件")
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(sources, key = { it.first.uri }) { (source, imported) ->
                    TextButton({ if (imported == null) selected = source else open(File(root, imported)) }, enabled = !busy) {
                        Column { Text(source.name); Text(date(source.recordedAt) + " · ${source.size / 1024} KB" + if (imported != null) " · 已导入（查看）" else "") }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(close) { Text("关闭") } })
    selected?.let { source ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("导入并转写") }, text = {
            Column { Text(source.name); Text("请先在系统录音机停止并保存录音"); Row { Checkbox(autoSummary, { autoSummary = it }); Text("转写完成后自动总结") } }
        }, confirmButton = { TextButton({ if (!busy) import(source, autoSummary); selected = null }) { Text("开始") } },
            dismissButton = { TextButton({ selected = null }) { Text("取消") } })
    }
}
