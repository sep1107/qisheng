// Copyright (C) by Ubaldo Porcheddu <ubaldo@eja.it>

package it.eja.vox

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.core.content.ContextCompat
import java.io.*
import kotlin.concurrent.thread
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

class MainActivity : ComponentActivity() {
    private val cloud by lazy {
        CloudApi(getSharedPreferences("vox_prefs", MODE_PRIVATE)) { message ->
            RecordingState.update { it.copy(message = message) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sharedUri = if (intent?.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        } else null

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppScreen(sharedUri)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AppScreen(sharedUri: Uri?) {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("vox_prefs", Context.MODE_PRIVATE) }

        var apiRevision by remember { mutableStateOf(0) }
        var language by remember { mutableStateOf(prefs.getString("language", "中文") ?: "中文") }
        val llmConfigured = remember(apiRevision) {
            ApiProfiles.load(prefs, "llm").any { it.enabled && it.url.isNotBlank() }
        }

        val recording by RecordingState.status
        val recordingState = recording.recording
        var showHistory by remember { mutableStateOf(false) }
        var showSources by remember { mutableStateOf(false) }
        var showExport by remember { mutableStateOf(false) }
        var showUpdate by remember { mutableStateOf(false) }
        var selectedSession by remember { mutableStateOf("") }
        val beginRecording = {
            ContextCompat.startForegroundService(context,
                Intent(context, RecordingService::class.java).setAction(RecordingService.START))
        }
        var isUploading by remember { mutableStateOf(false) }
        var htmlResponse by remember { mutableStateOf(wrapHtml("选择系统录音，或点击麦克风开始录音", false)) }
        var savedSummary by remember { mutableStateOf("") }
        var showRegenerate by remember { mutableStateOf(false) }
        var transcriptText by remember { mutableStateOf("") }
        var showSettings by remember { mutableStateOf(false) }
        var showActionMenu by remember { mutableStateOf(false) }
        var showTranslationDialog by remember { mutableStateOf(false) }
        var otherPrompt by remember { mutableStateOf("") }
        var targetLang by remember { mutableStateOf(language) }

        val alpha by rememberInfiniteTransition(label = "").animateFloat(
            initialValue = 1f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
            label = ""
        )

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                beginRecording()
            }
        }

        fun importAudio(uri: Uri, source: SourceRecording? = null, autoSummary: Boolean = false) {
            if (recording.processing || isUploading || RecordingService.busy) return
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)
                .setAction(RecordingService.IMPORT).setData(uri)
                .putExtra("sourceIdentity", source?.identity).putExtra("sourceName", source?.name)
                .putExtra("autoSummary", autoSummary)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                importAudio(it)
            }
        }
        val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    prefs.edit().putString("recording_tree", it.toString()).apply()
                }
            }
            showSources = true
        }
        fun selectSession(folder: File) {
            RecordingStore.recover(folder)
            selectedSession = folder.name
            transcriptText = RecordingStore.transcript(folder)
            savedSummary = RecordingStore.savedSummary(folder)
            htmlResponse = wrapHtml(savedSummary.ifBlank { transcriptText }, false)
            showHistory = false; showSources = false
        }
        if (showSources) SystemRecordingsDialog(prefs, File(context.filesDir, "recordings"), recording.processing || isUploading,
            chooseFolder = { showSources = false; treeLauncher.launch(null) },
            chooseFile = { showSources = false; importLauncher.launch(arrayOf("audio/*")) },
            close = { showSources = false }, import = { source, auto -> showSources = false; importAudio(Uri.parse(source.uri), source, auto) },
            open = { selectSession(it) })
        LaunchedEffect(sharedUri) { sharedUri?.let { importAudio(it) } }

        LaunchedEffect(recording.text, recording.session, recording.recording, recording.summary) {
            if (recording.session.isNotEmpty()) {
                selectedSession = recording.session
                transcriptText = recording.text
                savedSummary = recording.summary.ifBlank { RecordingStore.savedSummary(File(context.filesDir, "recordings/${recording.session}")) }
                htmlResponse = wrapHtml(when {
                    savedSummary.isNotBlank() -> savedSummary
                    else -> recording.text
                }, false)
            }
        }
        if (showHistory) HistoryDialog(File(context.filesDir, "recordings"), recording.processing || isUploading,
            close = { showHistory = false }, select = { selectSession(it) }, trashed = { id ->
                if (selectedSession == id) { selectedSession = ""; transcriptText = ""; savedSummary = ""; htmlResponse = wrapHtml("已移入回收站，可在历史中恢复", false) }
                RecordingState.update { if (it.session == id) RecordingStatus() else it }
            })

        fun generateSummary(refresh: Boolean) {
            if (isUploading || recording.processing || transcriptText.isBlank()) return
            val folder = selectedSession.takeIf { it.isNotEmpty() }?.let { File(context.filesDir, "recordings/$it") } ?: return
            isUploading = true
            val text = transcriptText
            thread {
                try {
                    val result = RecordingStore.summary(folder, refresh) { RecordingSummary.generate(cloud, text, language) }
                    runOnUiThread { savedSummary = result; htmlResponse = wrapHtml(result, false); isUploading = false
                        RecordingState.update { it.copy(message = "总结已保存，查看不会再次消耗额度") } }
                } catch (_: Exception) {
                    runOnUiThread { isUploading = false; htmlResponse = wrapHtml("总结失败，原文及已有总结仍保留，可稍后重试", true) }
                }
            }
        }
        val selectedFolder = File(context.filesDir, "recordings/$selectedSession")
        if (showExport && selectedSession.isNotEmpty()) ExportDialog(selectedFolder) { showExport = false }
        val incompleteImport = selectedSession.isNotEmpty() && File(selectedFolder, "import-incomplete.txt").exists() && !File(selectedFolder, "import-complete.txt").exists()
        val hasPending = selectedSession.isNotEmpty() && (incompleteImport || RecordingStore.pending(selectedFolder).isNotEmpty())
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        if (transcriptText.isNotEmpty() && llmConfigured) {
                            IconButton(onClick = { showActionMenu = true }, enabled = !recording.processing && !isUploading && !hasPending) {
                                Icon(Icons.Default.AutoAwesome, "AI 操作")
                            }
                        }
                    },
                    title = {
                        IconButton(
                            modifier = Modifier.size(64.dp),
                            enabled = !isUploading && (!recording.processing || recordingState),
                            onClick = {
                                if (!recordingState) {
                                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        beginRecording()
                                    } else { permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
                                } else {
                                    context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
                                }
                            }
                        ) {
                            if (isUploading) CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            else if (recordingState) Icon(Icons.Default.Stop, "停止录音", modifier = Modifier.size(48.dp), tint = Color.Red.copy(alpha = alpha))
                            else Icon(Icons.Default.Mic, "开始录音", modifier = Modifier.size(48.dp))
                        }
                    },
                    actions = {
                        TextButton(onClick = { showSources = true }, enabled = !recording.processing && !isUploading) { Text("系统录音") }
                        IconButton(onClick = { showHistory = true }, enabled = !recording.processing && !isUploading) { Icon(Icons.Default.History, "录音历史") }
                        IconButton(onClick = { showSettings = true }, enabled = !recording.processing && !isUploading) { Icon(Icons.Default.Settings, "设置") }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                Row {
                    if (selectedSession.isNotEmpty()) TextButton({ showExport = true }, enabled = !recording.processing && !isUploading) { Text("保存笔记") }
                }
                if (recording.liveMessage.isNotBlank()) Text(recording.liveMessage, Modifier.padding(horizontal = 12.dp))
                if (isUploading) { Text("正在生成 AI 结果，请稍候…", Modifier.padding(horizontal = 12.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                if (recording.processing && !recording.recording) {
                    Text(if (recording.totalSegments > 0) "已完成 ${recording.completedSegments}/${recording.totalSegments} 段 · 第 ${recording.currentSegment} 段 · ${recording.phase}" else "正在准备录音分段…", Modifier.padding(horizontal = 12.dp))
                    if (recording.uploadPercent >= 0) LinearProgressIndicator(progress = { recording.uploadPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text("${recording.seconds / 60}:${(recording.seconds % 60).toString().padStart(2, '0')} · ${recording.message}",
                    modifier = Modifier.padding(horizontal = 12.dp))
                if (hasPending && !recording.processing && !isUploading) {
                    if (hasPending) Text("还有录音分段未转写，补传完成后才能总结。", Modifier.padding(horizontal = 12.dp))
                    if (incompleteImport) Text("导入未完成，请重新导入原文件；当前部分不可总结。")
                    TextButton(enabled = !incompleteImport, onClick = {
                        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)
                            .setAction(RecordingService.RETRY).putExtra("session", selectedSession))
                    }) { Icon(Icons.Default.Refresh, null); Text("补传未完成分段") }
                }

                if (savedSummary.isNotBlank() && !recording.processing && !isUploading) Row {
                    TextButton({ htmlResponse = wrapHtml(transcriptText, false) }) { Text("查看原文") }
                    TextButton({ htmlResponse = wrapHtml(savedSummary, false) }) { Text("查看总结（已保存）") }
                }
                if (recordingState && recording.liveText.isNotBlank()) {
                    val liveScroll = rememberScrollState()
                    LaunchedEffect(recording.liveText) {
                        withFrameNanos { }
                        liveScroll.scrollTo(liveScroll.maxValue)
                    }
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(liveScroll)) {
                        Text("实时字幕预览 · 完整逐字稿随后按分段保存")
                        Text(recording.liveText)
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { WebView(it).apply { webViewClient = WebViewClient(); settings.javaScriptEnabled = false; setBackgroundColor(0) } },
                        update = {
                            if (it.tag != htmlResponse) {
                                it.tag = htmlResponse
                                it.loadDataWithBaseURL(null, htmlResponse, "text/html", "UTF-8", null)
                            }
                        }
                    )
                }

            }
        }

        if (showRegenerate) AlertDialog(onDismissRequest = { showRegenerate = false },
            title = { Text("重新生成总结？") }, text = { Text("将再次调用 AI 接口并消耗额度；成功后替换已保存总结。") },
            confirmButton = { TextButton({ showRegenerate = false; generateSummary(true) }) { Text("重新生成") } },
            dismissButton = { TextButton({ showRegenerate = false }) { Text("取消") } })
        if (showActionMenu) {
            AlertDialog(
                onDismissRequest = { showActionMenu = false },
                title = { Text("AI 操作") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            headlineContent = { Text(if (savedSummary.isBlank()) "生成总结" else "查看已保存总结（不调用 API）") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                if (savedSummary.isNotBlank()) htmlResponse = wrapHtml(savedSummary, false)
                                else generateSummary(false)
                            }
                        )
                        if (savedSummary.isNotBlank()) ListItem(
                            headlineContent = { Text("重新生成总结（消耗额度）") },
                            modifier = Modifier.clickable { showActionMenu = false; showRegenerate = true }
                        )
                        ListItem(
                            headlineContent = { Text("翻译") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                targetLang = language
                                showTranslationDialog = true
                            }
                        )
                        ListItem(
                            headlineContent = { Text("纠正语法") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                isUploading = true
                                callLLM("Fix grammar and spelling of the following text. Produce only one final corrected version.\n\n$transcriptText") {
                                    runOnUiThread { htmlResponse = it; isUploading = false }
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        TextField(
                            value = otherPrompt,
                            onValueChange = { otherPrompt = it },
                            label = { Text("其他要求") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (otherPrompt.isNotEmpty()) {
                            showActionMenu = false
                            isUploading = true
                            callLLM("$otherPrompt\n\n$transcriptText") {
                                runOnUiThread { htmlResponse = it; isUploading = false }
                            }
                        }
                    }) { Text("执行") }
                },
                dismissButton = { TextButton(onClick = { showActionMenu = false }) { Text("取消") } }
            )
        }

        if (showTranslationDialog) {
            AlertDialog(
                onDismissRequest = { showTranslationDialog = false },
                title = { Text("选择语言") },
                text = {
                    TextField(value = targetLang, onValueChange = { targetLang = it }, label = { Text("语言") }, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = {
                        showTranslationDialog = false
                        isUploading = true
                        callLLM("Translate the following text into $targetLang\n\n$transcriptText") {
                            runOnUiThread { htmlResponse = it; isUploading = false }
                        }
                    }) { Text("翻译") }
                },
                dismissButton = { TextButton(onClick = { showTranslationDialog = false }) { Text("取消") } }
            )
        }

        if (showSettings) {
            ApiSettingsDialog(prefs, checkUpdate = { showUpdate = true }, dismiss = { showSettings = false }, saved = {
                apiRevision++
                language = prefs.getString("language", "中文") ?: "中文"
                showSettings = false
            })
        }
        if (showUpdate) UpdateDialog(close = { showUpdate = false })
    }

    private fun callLLM(prompt: String, onResult: (String) -> Unit) {
        thread {
            try { onResult(wrapHtml(cloud.chat(prompt), false)) }
            catch (e: Exception) { onResult(wrapHtml(if (e is ApiUnavailable) e.message ?: "接口不可用" else "总结失败，请重试", true)) }
        }
    }

    private fun wrapHtml(content: String, isError: Boolean): String {
        val parser = Parser.builder().build()
        val document = parser.parse(content)
        val renderer = HtmlRenderer.builder().build()
        val htmlContent = renderer.render(document)

        val color = if (isError) "#C53030" else "#6200EE"
        val bg = if (isError) "#FFF5F5" else "#F9F9F9"

        return """
        <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            body{font-family:system-ui,sans-serif;padding:20px;line-height:1.6;background:#fff;}
            .card{background:$bg;border-left:5px solid $color;padding:15px;border-radius:4px;color:${if (isError) color else "#333"};}
            p{margin:0;white-space:pre-wrap;font-size:16px;}
            ul, ol { padding-left: 20px; }
            code { background: #eee; padding: 2px 4px; border-radius: 4px; }
            pre { background: #eee; padding: 10px; overflow-x: auto; border-radius: 4px; }
        </style></head>
        <body><div class="card">$htmlContent</div></body></html>
    """.trimIndent()
    }

}
