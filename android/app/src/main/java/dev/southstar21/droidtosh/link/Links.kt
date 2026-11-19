package dev.southstar21.droidtosh.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.southstar21.droidtosh.R
import dev.southstar21.droidtosh.diagnostics.Diagnostics
import dev.southstar21.droidtosh.protocol.v1.OpenUrl
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.TransportHub
import dev.southstar21.droidtosh.transport.newEnvelope
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Links and phone numbers between the devices (protocol/PROTOCOL-v2.md section 10). A link from the
 * Mac is never opened by itself: it becomes a notification, and the user's tap opens it through a
 * PendingIntent (which the system is always allowed to start, unlike a background activity start).
 */
object Links {
    private const val TAG = "DroidsyncLink"
    private const val CHANNEL_ID = "links"
    private const val MAX_NOTIFICATION_IDS = 16

    private var job: Job? = null
    private val nextId = AtomicInteger()

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            TransportHub.incoming(InboundFeature.LINK).collect { envelope ->
                if (envelope.hasOpenUrl()) onLinkFromMac(appContext, envelope.openUrl.url)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Sends [url] to the Mac; false if it isn't a valid link, the phone isn't connected, or the Mac
     * didn't negotiate `open_url` (links are never held for a later session).
     */
    fun send(url: String): Boolean {
        val link = LinkPolicy.validate(url, allowTel = false) ?: return false
        return TransportHub.send(newEnvelope { setOpenUrl(OpenUrl.newBuilder().setUrl(link.url)) })
    }

    private fun onLinkFromMac(context: Context, raw: String) {
        val link = LinkPolicy.validate(raw, allowTel = true)
        if (link == null) {
            Diagnostics.recorder.onInfo("link from Mac rejected")
            return
        }
        Diagnostics.recorder.onInfo("link from Mac: ${link.display}")
        post(context, link)
    }

    private fun post(context: Context, link: LinkPolicy.Link) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.links_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val id = nextId.getAndIncrement().mod(MAX_NOTIFICATION_IDS)
        val open = Intent(
            when (link) {
                is LinkPolicy.Tel -> Intent.ACTION_DIAL
                is LinkPolicy.Web -> Intent.ACTION_VIEW
            },
            when (link) {
                is LinkPolicy.Tel -> Uri.fromParts("tel", link.number, null)
                is LinkPolicy.Web -> Uri.parse(link.url)
            },
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Sent by the system on tap, which is always allowed to start an activity.
        val tap = PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (link) {
            is LinkPolicy.Tel -> context.getString(R.string.links_call_from_mac, link.display)
            is LinkPolicy.Web -> context.getString(R.string.links_open_from_mac, link.display)
        }
        val body = when (link) {
            is LinkPolicy.Tel -> context.getString(R.string.links_call_body)
            is LinkPolicy.Web -> link.url
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brand_glyph)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify("link", id, notification) }
            .onFailure { Log.w(TAG, "couldn't post link notification", it) }
    }
}
