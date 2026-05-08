// Copyright (C) by Ubaldo Porcheddu <ubaldo@eja.it>

package it.eja.vox

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

class MainActivity : ComponentActivity() {
    private var isRecording = false
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

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

        var url by remember { mutableStateOf(prefs.getString("url", "https://api.openai.com/v1/audio/transcriptions") ?: "") }
        var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
        var voiceModel by remember { mutableStateOf(prefs.getString("v_model", "") ?: "") }

        var llmUrl by remember { mutableStateOf(prefs.getString("llm_url", "") ?: "") }
        var llmAuth by remember { mutableStateOf(prefs.getString("llm_auth", "") ?: "") }
        var llmModel by remember { mutableStateOf(prefs.getString("llm_model", "") ?: "") }
        var language by remember { mutableStateOf(prefs.getString("language", "") ?: "") }

        var recordingState by remember { mutableStateOf(false) }
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
                isRecording = true
                recordingState = true
                startWavRecording(context)
            }
        }

        LaunchedEffect(sharedUri) {
            sharedUri?.let { uri ->
                isUploading = true
                thread {
                    try {
                        val tempFile = File(context.cacheDir, "shared_audio")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        processUpload(tempFile, url, token, voiceModel) { raw, html ->
                            runOnUiThread {
                                transcriptText = raw
                                htmlResponse = html
                                isUploading = false
                                tempFile.delete()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            htmlResponse = wrapHtml("Error: ${e.message}", true)
                            isUploading = false
                        }
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    navigationIcon = {
                        if (transcriptText.isNotEmpty() && llmUrl.isNotEmpty()) {
                            IconButton(onClick = { showActionMenu = true }) {
                                Icon(Icons.Default.AutoAwesome, null)
                            }
                        }
                    },
                    title = {
                        IconButton(
                            modifier = Modifier.size(64.dp),
                            enabled = !isUploading,
                            onClick = {
                                if (!recordingState) {
                                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        isRecording = true
                                        recordingState = true
                                        startWavRecording(context)
                                    } else { permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
                                } else {
                                    isRecording = false
                                    recordingState = false
                                    isUploading = true
                                    thread {
                                        Thread.sleep(500)
                                        processUpload(File(context.cacheDir, "rec.wav"), url, token, voiceModel) { raw, html ->
                                            runOnUiThread {
                                                transcriptText = raw
                                                htmlResponse = html
                                                isUploading = false
                                            }
                                        }
                                    }
                                }
                            }
                        ) {
                            if (isUploading) CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                            else if (recordingState) Icon(Icons.Default.Stop, null, modifier = Modifier.size(48.dp), tint = Color.Red.copy(alpha = alpha))
                            else Icon(Icons.Default.Mic, null, modifier = Modifier.size(48.dp))
                        }
                    },
                    actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, null) } }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding)) {
                if (isUploading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { WebView(it).apply { webViewClient = WebViewClient(); settings.javaScriptEnabled = true; setBackgroundColor(0) } },
                    update = { it.loadDataWithBaseURL(null, htmlResponse, "text/html", "UTF-8", null) }
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
                                var p = "Summarize the following text using only words."
                                if (language.isNotEmpty()) p += " Using the $language language."
                                p += "\n\n$transcriptText"
                                callLLM(llmUrl, llmAuth, llmModel, p) { runOnUiThread { htmlResponse = it; isUploading = false } }
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
                                callLLM(llmUrl, llmAuth, llmModel, "Fix grammar and spelling of the following text. Produce only one final corrected version.\n\n$transcriptText") {
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
                            callLLM(llmUrl, llmAuth, llmModel, "$otherPrompt\n\n$transcriptText") {
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
                        callLLM(llmUrl, llmAuth, llmModel, "Translate the following text into $targetLang\n\n$transcriptText") {
                            runOnUiThread { htmlResponse = it; isUploading = false }
                        }
                    }) { Text("Translate") }
                },
                dismissButton = { TextButton(onClick = { showTranslationDialog = false }) { Text("Cancel") } }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Configuration") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("ASR", style = MaterialTheme.typography.titleSmall)
                        TextField(value = url, onValueChange = { url = it }, label = { Text("API URL") })
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(value = token, onValueChange = { token = it }, label = { Text("Auth Token") })
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(value = voiceModel, onValueChange = { voiceModel = it }, label = { Text("Model") })

                        Spacer(modifier = Modifier.height(16.dp))
                        Text("LLM", style = MaterialTheme.typography.titleSmall)
                        TextField(value = llmUrl, onValueChange = { llmUrl = it }, label = { Text("API URL") })
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(value = llmAuth, onValueChange = { llmAuth = it }, label = { Text("Auth Token") })
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(value = llmModel, onValueChange = { llmModel = it }, label = { Text("Model") })
                        Spacer(modifier = Modifier.height(4.dp))
                        TextField(value = language, onValueChange = { language = it }, label = { Text("Language") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit()
                            .putString("url", url).putString("token", token).putString("v_model", voiceModel)
                            .putString("llm_url", llmUrl).putString("llm_auth", llmAuth)
                            .putString("llm_model", llmModel).putString("language", language)
                            .apply()
                        showSettings = false
                    }) { Text("Save") }
                }
            )
        }
    }

    private fun processUpload(file: File, url: String, token: String, model: String, onResult: (String, String) -> Unit) {
        try {
            val fileName = if (file.name.endsWith(".wav")) "audio.wav" else "audio.mp3"
            val mediaType = (if (fileName.endsWith(".wav")) "audio/wav" else "audio/mpeg").toMediaType()
            val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, file.asRequestBody(mediaType))
                .addFormDataPart("model", model.ifEmpty { "whisper-1" }).build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(requestBody).build()
            client.newCall(request).execute().use { response ->
                val res = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val text = JSONObject(res).optString("text", "")
                    onResult(text, wrapHtml(text, false))
                } else {
                    val err = try { JSONObject(res).getJSONObject("error").getString("message") } catch (e: Exception) { "Code ${response.code}" }
                    onResult("", wrapHtml(err, true))
                }
            }
        } catch (e: Exception) { onResult("", wrapHtml("Network Error: ${e.message}", true)) }
    }

    private fun callLLM(url: String, auth: String, model: String, prompt: String, onResult: (String) -> Unit) {
        thread {
            try {
                val json = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().put(JSONObject().apply { put("role", "user"); put("content", prompt) }))
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).header("Authorization", "Bearer $auth").post(body).build()
                client.newCall(request).execute().use { response ->
                    val res = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val out = JSONObject(res).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
                        onResult(wrapHtml(out, false))
                    } else onResult(wrapHtml("LLM Error: ${response.code}", true))
                }
            } catch (e: Exception) { onResult(wrapHtml("LLM Network Error: ${e.message}", true)) }
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

    @SuppressLint("MissingPermission")
    private fun startWavRecording(context: Context) {
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
        val outFile = File(context.cacheDir, "rec.wav")
        thread {
            val data = ByteArray(bufferSize)
            FileOutputStream(outFile).use { fos ->
                fos.write(ByteArray(44))
                recorder.startRecording()
                while (isRecording) {
                    val read = recorder.read(data, 0, data.size)
                    if (read > 0) fos.write(data, 0, read)
                }
                recorder.stop(); recorder.release(); fos.flush(); fos.fd.sync()
            }
            writeWavHeader(outFile)
        }
    }

    private fun writeWavHeader(file: File) {
        val audioLen = file.length() - 44
        val byteRate = 16 * sampleRate / 8
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((audioLen + 36).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1.toShort()); putShort(1.toShort())
            putInt(sampleRate); putInt(byteRate); putShort(2.toShort()); putShort(16.toShort())
            put("data".toByteArray()); putInt(audioLen.toInt())
        }.array()
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header); it.fd.sync() }
    }
}