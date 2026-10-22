package dev.southstar21.droidtosh

import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Older builds could link the Mac as a CompanionDeviceManager companion device. That feature is
 * gone, so this drops any association such a build left behind, plus its preferences, once per
 * process. Delete this file a few releases after the removal, once nobody upgrades from those builds.
 */
object LegacyCompanionCleanup {
    private const val TAG = "DroidsyncCleanup"
    private const val OLD_PREFS = "droidsync_companion"
    private val done = AtomicBoolean(false)

    fun run(context: Context) {
        if (!done.compareAndSet(false, true)) return
        val app = context.applicationContext
        thread(name = "legacy-companion-cleanup", isDaemon = true) {
            runCatching {
                app.deleteSharedPreferences(OLD_PREFS)
                if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)) return@runCatching
                val manager = app.getSystemService(CompanionDeviceManager::class.java) ?: return@runCatching
                manager.myAssociations.forEach { manager.disassociate(it.id) }
            }.onFailure { Log.w(TAG, "couldn't remove old companion associations", it) }
        }
    }
}
