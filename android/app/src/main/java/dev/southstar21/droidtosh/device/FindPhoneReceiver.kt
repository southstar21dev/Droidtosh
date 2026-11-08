package dev.southstar21.droidtosh.device

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.southstar21.droidtosh.BuildConfig

/** The ringing notification's Stop action and delete intent. */
class FindPhoneReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) FindPhone.stopOnPhone()
    }

    companion object {
        private const val ACTION_STOP = "${BuildConfig.APPLICATION_ID}.device.FIND_PHONE_STOP"

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, FindPhoneReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
