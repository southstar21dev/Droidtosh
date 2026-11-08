package dev.southstar21.droidtosh.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log

/**
 * The Find my phone ring: the default alarm sound (falling back to the ringtone, then the
 * notification sound) looping on the alarm stream at full volume, a repeating vibration, and a
 * high-priority alarm notification with a Stop action and a full-screen intent to [FindPhoneActivity].
 * The alarm stream's volume is restored when the ring stops.
 *
 * [onPlaybackError] is called if the player fails after it started.
 */
class AndroidAlarm(
    private val context: Context,
    private val onPlaybackError: () -> Unit,
) : Alarm {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Find my phone", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while your Mac makes this phone ring"
                // The ring plays its own sound on the alarm stream.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    @Synchronized
    override fun start(): Boolean {
        stopPlayback()
        savedVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }.onFailure { Log.w(TAG, "can't raise the alarm volume", it) }
        val started = soundUris().firstNotNullOfOrNull(::play)
        if (started == null) {
            restoreVolume()
            return false
        }
        player = started
        vibrate()
        runCatching { notifications.notify(NOTIFICATION_ID, buildNotification()) }
            .onFailure { Log.w(TAG, "can't post the ringing notification", it) }
        return true
    }

    @Synchronized
    override fun stop() {
        stopPlayback()
        runCatching { vibrator?.cancel() }
        restoreVolume()
        notifications.cancel(NOTIFICATION_ID)
    }

    private fun soundUris(): List<Uri> = listOfNotNull(
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    ).distinct()

    private fun play(uri: Uri): MediaPlayer? {
        val media = MediaPlayer()
        return try {
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            media.setDataSource(context, uri)
            media.isLooping = true
            media.prepare()
            media.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "playback error $what/$extra")
                onPlaybackError()
                true
            }
            media.start()
            media
        } catch (error: Exception) {
            Log.w(TAG, "can't play $uri", error)
            media.release()
            null
        }
    }

    private fun vibrate() {
        val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, 0)
        runCatching {
            vibrator?.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        }.onFailure { Log.w(TAG, "can't vibrate", it) }
    }

    private fun stopPlayback() {
        player?.let { media ->
            runCatching { media.stop() }
            media.release()
        }
        player = null
    }

    private fun restoreVolume() {
        val volume = savedVolume ?: return
        savedVolume = null
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
    }

    private fun buildNotification(): Notification {
        val stop = FindPhoneReceiver.stopIntent(context)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Your Mac is looking for this phone")
            .setContentText("Tap Stop to silence it")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(activityIntent())
            .setDeleteIntent(stop)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                    "Stop",
                    stop,
                ).build()
            )
        if (canUseFullScreenIntent()) builder.setFullScreenIntent(activityIntent(), true)
        return builder.build()
    }

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        NOTIFICATION_ID,
        Intent(context, FindPhoneActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // From Android 14 the full-screen intent permission is only pre-granted to calling and alarm apps.
    private fun canUseFullScreenIntent(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || notifications.canUseFullScreenIntent()

    companion object {
        private const val TAG = "DroidsyncFind"
        private const val CHANNEL_ID = "find_phone"
        const val NOTIFICATION_ID = 60
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 600)
    }
}
