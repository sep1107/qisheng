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
        var selectedSession by remember { mutableStateOf("") }
        val beginRecording = {
            ContextCompat.startForegroundService(context,
                Intent(context, RecordingService::class.java).setAction(RecordingService.START))
        }
        var isUploading by remember { mutableStateOf(false) }
        var htmlResponse by remember { mutableStateOf(wrapHtml("Ready...", false)) }
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

        fun importAudio(uri: Uri) {
            if (recording.processing || isUploading) return
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)
                .setAction(RecordingService.IMPORT).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                importAudio(it)
            }
        }
        LaunchedEffect(sharedUri) { sharedUri?.let { importAudio(it) } }

        LaunchedEffect(recording.text, recording.session) {
            if (recording.session.isNotEmpty()) {
                selectedSession = recording.session
                transcriptText = recording.text
                htmlResponse = wrapHtml(recording.text, false)
            }
        }
        if (showHistory) {
            val sessions = File(context.filesDir, "recordings").listFiles()
                ?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()
            AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("录音历史（音频保存在本机）") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    sessions.forEach { folder ->
                        TextButton(onClick = {
                            RecordingStore.recover(folder)
                            selectedSession = folder.name
                            transcriptText = RecordingStore.transcript(folder)
                            val summary = File(folder, "summary.txt")
                            htmlResponse = wrapHtml(if (summary.exists()) summary.readText() else transcriptText, false)
                            showHistory = false
                        }) { Text(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(folder.name.toLong())) + " · 待转写 ${RecordingStore.pending(folder).size} 段") }
                    }
                } }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } })
        }

        val selectedFolder = File(context.filesDir, "recordings/$selectedSession")
        val incompleteImport = selectedSession.isNotEmpty() && File(selectedFolder, "import-incomplete.txt").exists() && !File(selectedFolder, "import-complete.txt").exists()
        val hasPending = selectedSession.isNotEmpty() && (incompleteImport || RecordingStore.pending(selectedFolder).isNotEmpty())
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        if (transcriptText.isNotEmpty() && llmConfigured) {
                            IconButton(onClick = { showActionMenu = true }, enabled = !recording.processing && !isUploading && !hasPending) {
                                Icon(Icons.Default.AutoAwesome, null)
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
                        TextButton(onClick = { importLauncher.launch(arrayOf("audio/*")) }, enabled = !recording.processing && !isUploading) { Text("导入") }
                        IconButton(onClick = { showHistory = true }, enabled = !recording.processing) { Icon(Icons.Default.History, "录音历史") }
                        IconButton(onClick = { showSettings = true }, enabled = !recording.processing) { Icon(Icons.Default.Settings, "设置") }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                if (isUploading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("${recording.seconds / 60}:${(recording.seconds % 60).toString().padStart(2, '0')} · ${recording.message}",
                    modifier = Modifier.padding(horizontal = 12.dp))
                if (selectedSession.isNotEmpty() && !recording.processing) {
                    if (hasPending) Text("还有录音分段未转写，补传完成后才能总结。", Modifier.padding(horizontal = 12.dp))
                    if (incompleteImport) Text("导入未完成，请重新导入原文件；当前部分不可总结。")
                    TextButton(enabled = !incompleteImport, onClick = {
                        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java)
                            .setAction(RecordingService.RETRY).putExtra("session", selectedSession))
                    }) { Icon(Icons.Default.Refresh, null); Text("补传未完成分段") }
                }

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

        if (showActionMenu) {
            AlertDialog(
                onDismissRequest = { showActionMenu = false },
                title = { Text("AI Actions") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            headlineContent = { Text("Summary") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                isUploading = true
                                val folder = selectedSession.takeIf { it.isNotEmpty() }?.let { File(context.filesDir, "recordings/$it") }
                                summarize(transcriptText, language) { result, success ->
                                    if (success && folder != null) RecordingStore.atomicText(File(folder, "summary.txt"), result)
                                    runOnUiThread { htmlResponse = wrapHtml(result, !success); isUploading = false }
                                }
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Translation") },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                showActionMenu = false
                                targetLang = language
                                showTranslationDialog = true
                            }
                        )
                        ListItem(
                            headlineContent = { Text("Fix Grammar") },
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
                            label = { Text("Other") },
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
                    }) { Text("Run") }
                },
                dismissButton = { TextButton(onClick = { showActionMenu = false }) { Text("Cancel") } }
            )
        }

        if (showTranslationDialog) {
            AlertDialog(
                onDismissRequest = { showTranslationDialog = false },
                title = { Text("Select Language") },
                text = {
                    TextField(value = targetLang, onValueChange = { targetLang = it }, label = { Text("Language") }, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(onClick = {
                        showTranslationDialog = false
                        isUploading = true
                        callLLM("Translate the following text into $targetLang\n\n$transcriptText") {
                            runOnUiThread { htmlResponse = it; isUploading = false }
                        }
                    }) { Text("Translate") }
                },
                dismissButton = { TextButton(onClick = { showTranslationDialog = false }) { Text("Cancel") } }
            )
        }

        if (showSettings) {
            ApiSettingsDialog(prefs, dismiss = { showSettings = false }, saved = {
                apiRevision++
                language = prefs.getString("language", "中文") ?: "中文"
                showSettings = false
            })
        }
    }

    private fun callLLM(prompt: String, onResult: (String) -> Unit) {
        thread {
            try { onResult(wrapHtml(cloud.chat(prompt), false)) }
            catch (e: Exception) { onResult(wrapHtml(if (e is ApiUnavailable) e.message ?: "接口不可用" else "总结失败，请重试", true)) }
        }
    }

    private fun summarize(text: String, language: String, result: (String, Boolean) -> Unit) {
        thread {
            try {
                val instruction = "总结录音：保留重要事实、数字、决定和待办，不编造。输出语言：${language.ifBlank { "中文" }}。"
                fun request(part: String) = cloud.chat("$instruction\n\n$part")
                var parts = RecordingStore.promptChunks(text)
                var rounds = 0
                while (parts.size > 1) {
                    check(rounds++ < 8) { "总结过长，请稍后重试" }
                    parts = RecordingStore.promptChunks(parts.map { request(it) }.joinToString("\n\n"))
                }
                result(request(parts.singleOrNull() ?: ""), true)
            } catch (e: Exception) {
                result(if (e is ApiUnavailable) e.message ?: "接口不可用" else "总结失败，原录音和转写仍保留，请重试。", false)
            }
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
