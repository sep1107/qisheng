package it.eja.vox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

/** Called only after the downloaded APK has passed the update checks. */
object ApkInstaller {
    const val RESULT = "it.eja.vox.INSTALL_RESULT"

    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        @Suppress("DEPRECATION")
        val version = context.packageManager.getPackageArchiveInfo(apk.path, 0)!!.versionCode
        val id = installer.createSession(params)
        UpdateReopen.arm(context, version)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(context, id,
                    Intent(context, InstallResultReceiver::class.java).setAction(RESULT)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND), flags)
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            UpdateReopen.cancel(context)
            runCatching { installer.abandonSession(id) }
            throw error
        }
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApkInstaller.RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        android.util.Log.i("QishengUpdate", "Install callback status=$status")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // This recorder is Android 8.1. Newer Android limits background launches.
                if (Build.VERSION.SDK_INT < 29) UpdateReopen.afterInstall(context)
            }
            else -> {
                UpdateReopen.cancel(context)
                Toast.makeText(context, "更新未完成，请重新尝试安装（$status）", Toast.LENGTH_LONG).show()
            }
        }
    }
}
