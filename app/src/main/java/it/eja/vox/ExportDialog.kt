package it.eja.vox

import android.content.ClipData
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
    var enabled by remember { mutableStateOf(prefs.getBoolean("receiver_enabled", false)) }
    var kind by remember { mutableStateOf(prefs.getString("receiver_kind", "Mac") ?: "Mac") }
    var url by remember { mutableStateOf(prefs.getString("receiver_url", "").orEmpty()) }
    var token by remember { mutableStateOf(prefs.getString("receiver_token", "").orEmpty()) }
    var confirmSend by remember { mutableStateOf(false) }
    fun work(action: suspend () -> String) {
        if (busy || RecordingService.busy) return
        busy = true; message = "正在处理…"
        scope.launch {
            try { message = action() }
            catch (_: Exception) { message = "操作未完成；原录音与文字仍保留。请检查空间、文件权限、接收地址或网络后重试。" }
            finally { busy = false }
        }
    }
    fun share(include: Boolean) {
        work {
            val files = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { check(mkdirs()) }
                val required = if (include) RecordingStore.segments(session).sumOf { it.length() } else 0L
                check(directory.usableSpace > required + 16 * 1024 * 1024) { "空间不足，请改用保存到文件" }
                (if (include) listOf(false, true) else listOf(false)).map { asAudio ->
                    File(directory, RecordingExport.filename(session, asAudio)).apply {
                        outputStream().use { RecordingExport.write(session, asAudio, it) }
                    }
                }
            }
            val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.exports", it) }
            val intent = Intent(if (include) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
                .setType(if (include) "*/*" else "text/markdown")
                .putExtra(Intent.EXTRA_SUBJECT, SessionLibrary.title(session))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (include) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            else intent.putExtra(Intent.EXTRA_STREAM, uris.single())
            intent.clipData = ClipData.newRawUri("柒声导出", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            context.startActivity(Intent.createChooser(intent, "分享笔记"))
            if (include) "已打开分享：笔记与纯音频 ZIP 为两个独立文件" else "已打开笔记分享，未调用 AI"
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
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("导出与发送") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("导出已保存的内容，不重新调用 AI。系统录音导入后的记录也支持。")
            Row { Checkbox(audio, { audio = it }, enabled = !busy); Text("同时导出音频 ZIP（可选，默认不选）") }
            Text("勾选后输出两个独立文件：Markdown 笔记、仅含 WAV 分段的 ZIP。系统原录音保持不变；大录音分享需额外缓存空间。")
            Row {
                TextButton({ if (audio) saveWithAudio.launch(null) else saveMarkdown.launch(RecordingExport.filename(session, false)) }, enabled = !busy) { Text("保存笔记") }
                TextButton({ share(audio) }, enabled = !busy) { Text("分享笔记") }
            }
            HorizontalDivider()
            Row { Checkbox(enabled, { enabled = it }, enabled = !busy); Text("可选：发送到自己的 Obsidian 接收端") }
            if (enabled) {
                Row { listOf("Mac", "服务器").forEach { option -> TextButton({ kind = option }, enabled = !busy) { Text(if (kind == option) "✓ $option" else option) } } }
                Text(if (kind == "Mac") "Mac 接收端写入本地库；需 Mac 在线，外网发送需自己的 HTTPS 入口。" else "服务器接收端保存后，还需你自己的同步链路进入 Obsidian；不默认连接 LiveSync。")
                OutlinedTextField(url, { url = it }, label = { Text("完整 HTTPS 接收地址") }, enabled = !busy, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(token, { token = it }, label = { Text("接收令牌（至少24位）") }, enabled = !busy, singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
            TextButton({
                runCatching {
                    if (enabled) { RecordingExport.receiverUrl(url); require(token.length >= 24 && token.all { it.code in 33..126 }) }
                    prefs.edit().putBoolean("receiver_enabled", enabled).putString("receiver_kind", kind)
                        .putString("receiver_url", url.trim()).putString("receiver_token", token.trim()).apply()
                    message = "接收配置已保存；不会自动发送录音"
                }.onFailure { message = "请填写有效的 HTTPS 接收地址，以及至少24位的ASCII令牌（不能有空格或换行）" }
            }, enabled = !busy) { Text("保存接收配置") }
            TextButton({ confirmSend = true }, enabled = enabled && !busy) { Text("发送文字到 Obsidian 接收端") }
            Text("接收端须兼容柒声协议；这里的发送只含原文与总结，不含音频。")
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message)
        }
    }, confirmButton = { TextButton(close, enabled = !busy) { Text("关闭") } })
    if (confirmSend) AlertDialog(onDismissRequest = { confirmSend = false }, title = { Text("发送到你配置的接收端？") },
        text = { Text("将发送这条录音的已有原文、总结、标题与时间到：${url.trim()}。不发送音频和 API 密钥。") },
        confirmButton = { TextButton({
            confirmSend = false
            val destination = url.trim(); val credential = token.trim()
            work { withContext(Dispatchers.IO) {
                var device = prefs.getString("export_device_id", "").orEmpty()
                if (device.isEmpty()) { device = UUID.randomUUID().toString(); prefs.edit().putString("export_device_id", device).commit() }
                runCatching { NoteReceiver.send(destination, credential, "$device-${session.name}", SessionLibrary.title(session).ifBlank { "柒声录音" }, RecordingExport.markdown(session)) }
                    .getOrElse { "发送未确认，内容仍在本机。${if (it is IllegalArgumentException || it is IllegalStateException) it.message.orEmpty() else "请检查网络后重试"}" }
            } }
        }) { Text("发送") } }, dismissButton = { TextButton({ confirmSend = false }) { Text("取消") } })
}
