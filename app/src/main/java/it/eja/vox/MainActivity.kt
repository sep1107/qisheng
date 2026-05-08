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
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var isRecording = false
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

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
        var recordingState by remember { mutableStateOf(false) }
        var isUploading by remember { mutableStateOf(false) }
        var htmlResponse by remember { mutableStateOf(wrapHtml("Ready...", false)) }
        var showSettings by remember { mutableStateOf(false) }

        val alpha by rememberInfiniteTransition(label = "").animateFloat(
            initialValue = 1f, targetValue = 0.2f,
            animationSpec = infiniteRepeatable(animation = tween(1000, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
            label = ""
        )

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) { isRecording = true; recordingState = true; startWavRecording(context) }
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
                        processUpload(tempFile, url, token) { res -> htmlResponse = res; isUploading = false; tempFile.delete() }
                    } catch (e: Exception) { htmlResponse = wrapHtml("File Error: ${e.message}", true); isUploading = false }
                }
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        IconButton(
                            modifier = Modifier.size(64.dp),
                            enabled = !isUploading,
                            onClick = {
                                if (!recordingState) {
                                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                        isRecording = true; recordingState = true; startWavRecording(context)
                                    } else { permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
                                } else {
                                    isRecording = false; recordingState = false; isUploading = true
                                    processUpload(File(context.cacheDir, "rec.wav"), url, token) { res -> htmlResponse = res; isUploading = false }
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

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Settings") },
                text = {
                    Column {
                        TextField(value = url, onValueChange = { url = it }, label = { Text("API Endpoint") })
                        Spacer(modifier = Modifier.height(8.dp))
                        TextField(value = token, onValueChange = { token = it }, label = { Text("Auth Token") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString("url", url).putString("token", token).apply()
                        showSettings = false
                    }) { Text("Save") }
                }
            )
        }
    }

    private fun processUpload(file: File, url: String, token: String, onResult: (String) -> Unit) {
        thread {
            try {
                // OpenAI strictly validates extensions. We mask shared files (OGG/AAC) as .mp3.
                // The underlying engine (FFmpeg) will still decode them correctly.
                val fileName = if (file.name.endsWith(".wav")) "audio.wav" else "audio.mp3"
                val mediaType = (if (fileName.endsWith(".wav")) "audio/wav" else "audio/mpeg").toMediaType()

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName, file.asRequestBody(mediaType))
                    .addFormDataPart("model", "whisper-1")
                    .build()

                val request = Request.Builder().url(url).header("Authorization", "Bearer $token").post(requestBody).build()

                OkHttpClient().newCall(request).execute().use { response ->
                    val res = response.body?.string() ?: ""
                    if (response.isSuccessful) {
                        val text = JSONObject(res).optString("text", "No transcription.")
                        onResult(wrapHtml(text, false))
                    } else {
                        val err = try { JSONObject(res).getJSONObject("error").getString("message") } catch (e: Exception) { "Code ${response.code}" }
                        onResult(wrapHtml(err, true))
                    }
                }
            } catch (e: Exception) { onResult(wrapHtml("Network Error: ${e.message}", true)) }
        }
    }

    private fun wrapHtml(content: String, isError: Boolean): String {
        val color = if (isError) "#C53030" else "#6200EE"
        val bg = if (isError) "#FFF5F5" else "#F9F9F9"
        return """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>body{font-family:system-ui,sans-serif;padding:20px;line-height:1.6;background:#fff;}
            .card{background:$bg;border-left:5px solid $color;padding:15px;border-radius:4px;color:${if (isError) color else "#333"};}
            h2{font-size:12px;text-transform:uppercase;margin:0 0 10px 0;color:$color;}
            p{margin:0;white-space:pre-wrap;font-size:16px;}</style></head>
            <body><div class="card"><h2>${if (isError) "Error" else "Transcription"}</h2><p>$content</p></div></body></html>
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
                recorder.stop(); recorder.release()
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
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header) }
    }
}