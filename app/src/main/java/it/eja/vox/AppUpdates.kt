package it.eja.vox

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.king.app.updater.AppUpdater
import com.king.app.updater.listener.SimpleDownloadListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private data class AppRelease(val tag: String, val code: Int, val url: String, val digest: String, val size: Long)
private fun latestRelease(): AppRelease? {
    val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    client.newCall(Request.Builder().url("https://api.github.com/repos/sep1107/qisheng/releases/latest")
        .header("Accept", "application/vnd.github+json").build()).execute().use { response ->
        if (response.code == 404) return null
        check(response.isSuccessful) { "检查失败，请稍后重试" }
        val json = response.body!!.byteStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val bytes = ByteArray(8192)
            while (true) { val n = input.read(bytes); if (n < 0) break; check(out.size() + n <= 1_048_576); out.write(bytes, 0, n) }
            out.toString("UTF-8")
        }
        val j = JSONObject(json)
        require(!j.optBoolean("draft") && !j.optBoolean("prerelease"))
        val tag = j.getString("tag_name"); val code = UpdatePolicy.versionCode(tag)
        val assets = j.getJSONArray("assets")
        val name = "qisheng-${tag.removePrefix("v")}.apk"
        val a = (0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull { it.optString("name") == name }
            ?: error("正式版本尚未提供柒声 APK")
        val url = a.getString("browser_download_url"); val digest = a.getString("digest"); val size = a.getLong("size")
        UpdatePolicy.validateAsset(url, digest, size)
        return AppRelease(tag, code, url, digest, size)
    }
}

@Suppress("DEPRECATION")
private fun verifiedApk(context: Context, file: File, release: AppRelease): File {
    require(file.length() == release.size) { "下载大小不符" }
    val sha = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; sha.update(bytes, 0, n) } }
    require(sha.digest().joinToString("") { "%02x".format(it) }.equals(release.digest.removePrefix("sha256:"), true)) { "下载校验失败" }
    val archive = context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) ?: error("安装包无效")
    val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    require(archive.packageName == context.packageName && archive.versionCode == release.code && archive.versionCode > installed.versionCode)
    val downloadedSigners = archive.signatures
    val installedSigners = installed.signatures
    require(!downloadedSigners.isNullOrEmpty() && !installedSigners.isNullOrEmpty() && downloadedSigners.map { it.toCharsString() }.toSet() == installedSigners.map { it.toCharsString() }.toSet()) { "签名不同，无法覆盖当前柒声" }
    val target = File(context.cacheDir, "updates/qisheng.apk").apply { parentFile!!.mkdirs() }
    file.copyTo(target, overwrite = true)
    return target
}

@Composable
fun UpdateDialog(close: () -> Unit) {
    val context = LocalContext.current
    var release by remember { mutableStateOf<AppRelease?>(null) }
    var message by remember { mutableStateOf("正在检查正式版本…") }
    var checking by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf<File?>(null) }
    var updater by remember { mutableStateOf<AppUpdater?>(null) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    LaunchedEffect(Unit) {
        try {
            release = withContext(Dispatchers.IO) { latestRelease() }
            message = when {
                release == null -> "仓库尚未发布可在线安装的正式版本"
                release!!.code <= BuildConfig.VERSION_CODE -> "当前已是最新正式版本"
                else -> "发现 ${release!!.tag}，约 ${release!!.size / 1024 / 1024} MB"
            }
        } catch (_: Exception) { message = "检查更新失败，请确认网络后重试" }
        finally { checking = false }
    }
    DisposableEffect(Unit) { onDispose { updater?.clearListener() } }
    AlertDialog(onDismissRequest = { if (!downloading) close() }, title = { Text("柒声在线更新 · ${BuildConfig.VERSION_NAME}") }, text = {
        Column { Text(message); if (checking || downloading) LinearProgressIndicator(); Text("仅从柒声 GitHub 正式版本下载。校验大小、SHA-256、包名、版本和签名后，由你确认安装。") }
    }, confirmButton = {
        when {
            ready != null -> TextButton({
                try {
                    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                        message = "授权后返回这里，再点确认安装"
                    } else {
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready!!)
                        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }
                } catch (_: Exception) { message = "无法打开安装器，请检查安装权限" }
            }) { Text("确认安装") }
            release != null && release!!.code > BuildConfig.VERSION_CODE -> TextButton({
                val version = release!!; downloading = true
                updater = AppUpdater.Builder(context).setUrl(version.url).setFilename("qisheng-${version.tag}.apk")
                    .setInstallApk(false).setShowNotification(false).setDeleteFileOnCancel(false)
                    .setDownloadListener(object : SimpleDownloadListener() {
                        override fun onProgress(progress: Long, total: Long) { handler.post { message = "下载中 ${if (total > 0) progress * 100 / total else 0}%" } }
                        override fun onSuccess(file: File) {
                            thread {
                                try {
                                    val verified = verifiedApk(context, file, version)
                                    handler.post { ready = verified; message = "安装包已校验，请确认安装"; downloading = false }
                                } catch (_: Exception) { handler.post { message = "安装包校验失败，请重新下载；未安装"; downloading = false } }
                            }
                        }
                        override fun onError(cause: Throwable) { handler.post { message = "下载失败，请重试"; downloading = false } }
                        override fun onCancel() { handler.post { message = "下载已取消"; downloading = false } }
                    }).build().also { it.start() }
            }, enabled = !downloading && !checking) { Text("下载更新") }
        }
    }, dismissButton = { TextButton({ if (downloading) { updater?.stop(); downloading = false } else close() }) { Text(if (downloading) "取消下载" else "关闭") } })
}
