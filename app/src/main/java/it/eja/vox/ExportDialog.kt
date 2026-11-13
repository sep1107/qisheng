package it.eja.vox

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun ExportDialog(session: File, close: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vox_prefs", 0) }
    val scope = rememberCoroutineScope()
    var audio by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val enabled = prefs.getBoolean("receiver_enabled", false)
    val url = prefs.getString("receiver_url", "").orEmpty()
    val token = prefs.getString("receiver_token", "").orEmpty()
    var confirmSend by remember { mutableStateOf(false) }
    fun work(sending: Boolean = false, action: suspend () -> String) {
        if (busy) return
        if (RecordingService.busy) {
            failed = true; message = "录音或转写仍在处理中，请完成后再试。"
            return
        }
        busy = true; failed = false
        message = if (sending) "正在发送文字，等待接收端确认…" else "正在保存笔记，请稍候…"
        scope.launch {
            try { message = action() }
            catch (e: Exception) {
                failed = true
                message = (if (sending) "发送未确认，文字仍在本机。" else "保存未完成，原录音与文字仍保留。") +
                    if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty()
                    else "请检查网络、接收配置、文件权限或空间后重试。"
            }
            finally { busy = false }
        }
    }
    val saveMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { work { withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(it, "w")!!.use { out -> RecordingExport.write(session, false, out) }
            "Markdown 已保存，没有调用 AI"
        } } }
    }
    val saveWithAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { work { withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            // Both files go into a new export folder; never overwrite an existing user file.
            val child = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, "柒声导出-${UUID.randomUUID().toString().take(8)}")
                ?: error("无法创建导出目录")
            listOf(false, true).forEach { asAudio ->
                val file = DocumentsContract.createDocument(resolver, child, if (asAudio) "application/zip" else "text/markdown", RecordingExport.filename(session, asAudio))
                    ?: error("无法创建导出文件")
                resolver.openOutputStream(file, "w")!!.use { RecordingExport.write(session, asAudio, it) }
            }
            "已保存到新导出目录：Markdown 笔记与纯音频 ZIP 分开存放"
        } } }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = {
        Column {
            Text("笔记与发送")
            if (message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        if (busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("可直接发送已有原文和总结，无需先保存文件，不重新调用 AI。系统录音导入后的记录也支持。")
            Row { Checkbox(audio, { audio = it }, enabled = !busy); Text("同时导出音频 ZIP（可选，默认不选）") }
            Text("勾选后输出两个独立文件：Markdown 笔记、仅含 WAV 分段的 ZIP。系统原录音保持不变。")
            Row {
                TextButton({ if (audio) saveWithAudio.launch(null) else saveMarkdown.launch(RecordingExport.filename(session, false)) }, enabled = !busy) { Text("保存笔记") }
            }
            HorizontalDivider()
            Text(if (enabled) "接收地址：$url" else "请先在设置中启用并配置自己的接收端。")
            Text(if (prefs.getBoolean("receiver_auto_send", false)) "总结完成后自动发送已开启，可在设置中关闭。" else "自动发送未开启，可在设置中选择总结完成后自动发送。")
            TextButton({ confirmSend = true }, enabled = enabled && !busy) { Text("发送文字到 Obsidian 接收端") }
            Text("接收端须兼容柒声协议；这里的发送只含原文与总结，不含音频。")
        }
    }, confirmButton = { TextButton(close, enabled = !busy) { Text("关闭") } })
    if (confirmSend) AlertDialog(onDismissRequest = { confirmSend = false }, title = { Text("发送到你配置的接收端？") },
        text = { Text("将发送这条录音的已有原文、总结、标题与时间到：${url.trim()}。不发送音频和 API 密钥。") },
        confirmButton = { TextButton({
            confirmSend = false
            val destination = url.trim(); val credential = token.trim()
            work(sending = true) { withContext(Dispatchers.IO) {
                val device = NoteDelivery.deviceId(prefs)
                val id = "$device-${session.name}"
                val markdown = RecordingExport.markdown(session)
                val result = NoteReceiver.send(destination, credential, id, SessionLibrary.title(session).ifBlank { "柒声录音" }, markdown)
                runCatching {
                    if (NoteDeliveryStore.ready(session) && NoteDeliveryStore.read(session, "id") == id &&
                        NoteDeliveryStore.read(session, "destination") == RecordingExport.receiverUrl(destination) &&
                        NoteDeliveryStore.read(session, "markdown") == markdown) NoteDeliveryStore.accept(session, result)
                }
                result
            } }
        }) { Text("发送") } }, dismissButton = { TextButton({ confirmSend = false }) { Text("取消") } })
}
