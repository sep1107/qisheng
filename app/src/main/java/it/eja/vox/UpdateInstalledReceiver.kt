package it.eja.vox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class UpdateInstalledReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // Android 10+ restricts background activity launches; the recorder runs Android 8.1.
        if (Build.VERSION.SDK_INT >= 29) return
        android.util.Log.i("QishengUpdate", "Received system package replacement")
        runCatching {
            UpdateReopen.open(context)
        }.onFailure { android.util.Log.w("QishengUpdate", "Unable to reopen after update", it) }
    }
}
