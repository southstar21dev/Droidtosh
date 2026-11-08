package dev.southstar21.droidtosh.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.content.FileProvider
import dev.southstar21.droidtosh.BuildConfig
import dev.southstar21.droidtosh.data.CredentialStore
import dev.southstar21.droidtosh.launch.OverlayPermission
import dev.southstar21.droidtosh.push.PushTokenStore
import dev.southstar21.droidtosh.transport.Base64URL
import dev.southstar21.droidtosh.transport.InboundFeature
import dev.southstar21.droidtosh.transport.TransportHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/** Android glue around [Diagnostics]: live snapshots, the redacted report and the log bundle. */
object DiagnosticsSharing {
    private const val TAG = "DroidsyncDiag"
    private const val REFRESH_MS = 250L // ≤ 4 Hz
    private const val LOG_LINES = 3000
    private const val DIR = "diagnostics"

    fun snapshot(context: Context): DiagnosticsSnapshot = Diagnostics.recorder.snapshot(
        pushTokenPresent = !PushTokenStore(context).token.isNullOrEmpty(),
        dropped = InboundFeature.entries.associateWith(TransportHub::droppedInbound),
        reliable = TransportHub.reliableStats(),
        batteryOptimizationIgnored = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName),
        overlayAllowed = OverlayPermission.granted(context),
    )

    /** Snapshots at most every 250 ms while collected; consecutive identical values are skipped. */
    fun snapshots(context: Context): Flow<DiagnosticsSnapshot> = flow {
        while (true) {
            emit(snapshot(context))
            delay(REFRESH_MS)
        }
    }.distinctUntilChanged { old, new -> old.copy(takenAtMs = 0) == new.copy(takenAtMs = 0) }
        .flowOn(Dispatchers.Default)

    // Off the main thread: building the redactor decrypts the stored pairing through the Keystore.
    suspend fun report(context: Context): String = withContext(Dispatchers.IO) {
        DiagnosticsReport.format(snapshot(context), header(), redactor(context))
    }

    /** Writes the report plus this process's recent logcat to a cache file and returns a share intent. */
    suspend fun shareLogsIntent(context: Context): Intent = withContext(Dispatchers.IO) {
        val report = report(context)
        val logs = redactor(context).redact(readOwnLogcat())
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "droidtosh-diagnostics-$stamp.txt")
        file.writeText(report + "\n\n[Log]\n" + logs)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Droidtosh diagnostics $stamp")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun readOwnLogcat(): String = runCatching {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "--pid=${Process.myPid()}", "-t", LOG_LINES.toString(),
        ).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
    }.getOrElse { error ->
        Log.w(TAG, "couldn't read logcat", error)
        "(logcat unavailable: ${error.javaClass.simpleName})"
    }

    private fun header() = ReportHeader(
        appVersion = BuildConfig.VERSION_NAME,
        androidVersion = Build.VERSION.RELEASE ?: "unknown",
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

    /** Knows the actual secrets so it can scrub them from logs even where a log line leaked one. */
    private fun redactor(context: Context): Redactor {
        val secrets = mutableListOf<String>()
        runCatching { CredentialStore(context).load() }.getOrNull()?.let { credentials ->
            secrets += credentials.room
            secrets += Base64URL.encode(credentials.psk)
            secrets += Base64.getEncoder().encodeToString(credentials.psk)
            // Fingerprints aren't secret; the redactor shortens any 64-hex value to 8 characters.
        }
        PushTokenStore(context).token?.let { secrets += it }
        return Redactor(secrets)
    }
}
