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
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
