package it.eja.vox

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** Android 8 recorder kills the immediate install callback during package replacement. */
internal object UpdateReopen {
    private fun prefs(context: Context) = context.getSharedPreferences("qisheng_update", Context.MODE_PRIVATE)
    private fun callback(context: Context, id: Int = 0) = PendingIntent.getBroadcast(context, id,
        Intent(context, UpdateReopenReceiver::class.java).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = SystemClock.elapsedRealtime()
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 30_000, callback(context))
        // System-owned repetition survives a receiver being killed mid-update.
        alarms.setRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + 90_000, 60_000, callback(context, 1))
    }

    fun arm(context: Context, version: Int) {
        if (Build.VERSION.SDK_INT >= 29 || version <= BuildConfig.VERSION_CODE) return
        check(prefs(context).edit().putInt("version", version)
            .putLong("expires", SystemClock.elapsedRealtime() + 600_000).commit())
        schedule(context)
    }

    fun cancel(context: Context) {
        prefs(context).edit().clear().commit()
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(callback(context))
        alarms.cancel(callback(context, 1))
    }

    fun check(context: Context) {
        val pending = prefs(context)
        val version = pending.getInt("version", 0)
        if (version == 0) return
        when {
            SystemClock.elapsedRealtime() >= pending.getLong("expires", 0) -> cancel(context)
            BuildConfig.VERSION_CODE >= version -> open(context)
        }
    }

    fun open(context: Context) {
        cancel(context)
        android.util.Log.i("QishengUpdate", "Opening updated app version=${BuildConfig.VERSION_CODE}")
        context.startActivity(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
}

class UpdateReopenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { UpdateReopen.check(context) }
}
