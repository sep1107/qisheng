// Copyright (C) by Ubaldo Porcheddu <ubaldo@eja.it>

package it.eja.vox

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var isRecording = false
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private fun getAppId(context: Context): String {
        val prefs = context.getSharedPreferences("vox_prefs", Context.MODE_PRIVATE)
        var uuid = prefs.getString("app_uuid", null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            prefs.edit().putString("app_uuid", uuid).apply()
        }
        return uuid
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
        var uploadUrl by remember { mutableStateOf(prefs.getString("url", "https://vox.eja.it") ?: "") }

        var recordingState by remember { mutableStateOf(false) }
        var isUploading by remember { mutableStateOf(false) }

        var htmlResponse by remember { mutableStateOf("<html><body></body></html>") }
        var showSettings by remember { mutableStateOf(false) }
        val appId = remember { getAppId(context) }

        val infiniteTransition = rememberInfiniteTransition(label = "recording")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ), label = "alpha"
        )

        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                isRecording = true
                recordingState = true
                startWavRecording(context)
            }
        }

        LaunchedEffect(sharedUri) {
            sharedUri?.let {
                isUploading = true
                uploadFile(context, it, uploadUrl, appId) { res ->
                    htmlResponse = res
                    isUploading = false
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("VOX") },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.padding(padding).padding(16.dp)) {

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isUploading,
                    onClick = {
                        if (!recordingState) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                isRecording = true
                                recordingState = true
                                startWavRecording(context)
                            } else {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            isRecording = false
                            recordingState = false
                            isUploading = true
                            val file = File(context.cacheDir, "rec.wav")
                            uploadFile(context, Uri.fromFile(file), uploadUrl, appId) { res ->
                                htmlResponse = res
                                isUploading = false
                            }
                        }
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (recordingState) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.Red.copy(alpha = alpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Stop & Send")
                        } else if (isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Processing...")
                        } else {
                            Text("Record")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isUploading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { WebView(it).apply {
                        webViewClient = WebViewClient()
                        settings.javaScriptEnabled = true
                        setBackgroundColor(0)
                    } },
                    update = { it.loadDataWithBaseURL(uploadUrl, htmlResponse, "text/html", "UTF-8", null) }
                )
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("Upload URL") },
                text = {
                    TextField(
                        value = uploadUrl,
                        onValueChange = { uploadUrl = it },
                        label = { Text("Server URL") }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString("url", uploadUrl).apply()
                        showSettings = false
                    }) { Text("Save") }
                }
            )
        }
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
                recorder.stop()
                recorder.release()
            }
            writeWavHeader(outFile)
        }
    }

    private fun writeWavHeader(file: File) {
        val totalAudioLen = file.length() - 44
        val totalDataLen = totalAudioLen + 36
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(totalDataLen.toInt())
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 16 / 8).toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(totalAudioLen.toInt())
        }.array()

        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header) }
    }

    private fun uploadFile(context: Context, uri: Uri, targetUrl: String, appId: String, onResult: (String) -> Unit) {
        thread {
            try {
                val boundary = "Boundary-${System.currentTimeMillis()}"
                val conn = URL(targetUrl).openConnection() as HttpURLConnection
                conn.doOutput = true
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

                DataOutputStream(conn.outputStream).use { out ->
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"uuid\"\r\n\r\n")
                    out.writeBytes(appId)
                    out.writeBytes("\r\n")
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
                    out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                    context.contentResolver.openInputStream(uri)?.use { it.copyTo(out) }
                    out.writeBytes("\r\n--$boundary--\r\n")
                    out.flush()
                }

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                onResult(response)
            } catch (e: Exception) {
                onResult("<html><body>Error: ${e.localizedMessage}</body></html>")
            }
        }
    }
}