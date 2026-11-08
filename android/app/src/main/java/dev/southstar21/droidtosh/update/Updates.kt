package dev.southstar21.droidtosh.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import dev.southstar21.droidtosh.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What the updater is doing; the screens render it. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val build: Int) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class ReadyToInstall(val version: String) : UpdateState
    data class Installing(val version: String) : UpdateState
    /** [available] is the update that was found, so the user can retry installing it. */
    data class Failed(val message: String, val available: Available? = null) : UpdateState
}

/**
 * Over-the-air updates from the GitHub releases of [BuildConfig.UPDATE_REPO] (docs/RELEASING.md). It checks
 * when the app or the connection service starts and every [CHECK_INTERVAL_MS] while the process lives, and
 * on demand. Installing always starts from the user's tap.
 */
object Updates {
    private const val PREFERENCES = "droidtosh_updates"
    private const val BETA_KEY = "beta"
    private const val LAST_CHECK_KEY = "last_check_ms"
    const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val APK_NAME = "droidtosh-update.apk"
    private const val TIMEOUT_MS = 15_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    private val mutableBeta = MutableStateFlow(false)
    private var loop: Job? = null
    private var work: Job? = null

    /** The update the last check found; kept so Install knows what to download. */
    @Volatile
    private var candidate: UpdateCandidate? = null

    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    val betaEnabled: StateFlow<Boolean> = mutableBeta.asStateFlow()

    /** Starts the periodic check for this process; safe to call from every entry point. */
    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        mutableBeta.value = preferences(app).getBoolean(BETA_KEY, false)
        if (loop?.isActive == true) return
        loop = scope.launch {
            while (isActive) {
                val sinceLast = System.currentTimeMillis() - preferences(app).getLong(LAST_CHECK_KEY, 0L)
                if (sinceLast >= CHECK_INTERVAL_MS || sinceLast < 0) runCheck(app, automatic = true)
                delay((CHECK_INTERVAL_MS - sinceLast).coerceIn(60_000L, CHECK_INTERVAL_MS))
            }
        }
    }

    /** "Check for updates". */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        if (busy()) return
        work = scope.launch { runCheck(app, automatic = false) }
    }

    fun setBetaEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        preferences(app).edit().putBoolean(BETA_KEY, enabled).apply()
        mutableBeta.value = enabled
        // The other channel may offer something different (or nothing).
        candidate = null
        if (!busy()) work = scope.launch { runCheck(app, automatic = false) }
    }

    /**
     * Downloads, verifies and installs the update found by the last check. If Droidtosh may not install
     * apps yet, it opens that system setting instead; the user taps Install again afterwards.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val update = candidate ?: return
        if (busy()) return
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        work = scope.launch {
            val available = UpdateState.Available(update.version, update.build)
            try {
                val apk = download(app, update)
                mutableState.value = UpdateState.ReadyToInstall(update.version)
                commit(app, apk, update)
                mutableState.value = UpdateState.Installing(update.version)
            } catch (error: Exception) {
                mutableState.value = UpdateState.Failed(error.message ?: "Update failed", available)
            }
        }
    }

    /** Reported by [UpdateInstallReceiver] when the install didn't go through. */
    internal fun onInstallFailed(message: String) {
        val update = candidate
        mutableState.value = UpdateState.Failed(
            message,
            update?.let { UpdateState.Available(it.version, it.build) },
        )
    }

    /** The user dismissed Android's confirmation: offer the update again. */
    internal fun onInstallAborted() {
        candidate?.let { mutableState.value = UpdateState.Available(it.version, it.build) }
    }

    private fun busy(): Boolean = when (mutableState.value) {
        UpdateState.Checking, is UpdateState.Downloading, is UpdateState.ReadyToInstall, is UpdateState.Installing -> true
        else -> work?.isActive == true
    }

    private fun runCheck(app: Context, automatic: Boolean) {
        // A download or install in progress owns the state.
        if (automatic && busy()) return
        val previous = when (val current = mutableState.value) {
            is UpdateState.Available -> current
            is UpdateState.Failed -> current.available
            else -> null
        }
        mutableState.value = UpdateState.Checking
        mutableState.value = try {
            val found = UpdateChecker(BuildConfig.UPDATE_REPO, ::httpGet)
                .check(installedVersion, mutableBeta.value)
            preferences(app).edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
            candidate = found
            if (found == null) UpdateState.UpToDate else UpdateState.Available(found.version, found.build)
        } catch (error: Exception) {
            // Keep offering an update an earlier check found.
            UpdateState.Failed(checkFailure(error), previous)
        }
    }

    /** This build's version; a version name that isn't semver (an odd local build) counts as 0.0.0. */
    private val installedVersion: SemVer
        get() = SemVer.parse(BuildConfig.VERSION_NAME) ?: SemVer(0, 0, 0)

    private fun checkFailure(error: Exception): String = when (error) {
        is UpdateHttpException -> "Couldn't check for updates (HTTP ${error.status})"
        else -> "Couldn't check for updates"
    }

    private fun download(app: Context, update: UpdateCandidate): File {
        val directory = File(app.cacheDir, "updates").apply { mkdirs() }
        val file = File(directory, APK_NAME)
        val connection = open(update.apk.downloadUrl)
        try {
            val total = update.apk.size.takeIf { it > 0 } ?: connection.contentLengthLong
            mutableState.value = UpdateState.Downloading(update.version, 0f)
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    var lastReported = 0f
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val progress = if (total > 0) (written.toFloat() / total).coerceAtMost(1f) else 0f
                        if (progress - lastReported >= 0.01f) {
                            lastReported = progress
                            mutableState.value = UpdateState.Downloading(update.version, progress)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val expected = checkNotNull(update.manifest.android).sha256
        val actual = file.inputStream().use { Sha256.of(it) }
        if (!Sha256.matches(actual, expected)) {
            file.delete()
            throw IOException("The download didn't match its checksum")
        }
        return file
    }

    private fun commit(app: Context, apk: File, update: UpdateCandidate) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Silent once Droidtosh installed itself before; Android asks the user otherwise.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("droidtosh.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val status = PendingIntent.getBroadcast(
                app,
                sessionId,
                Intent(app, UpdateInstallReceiver::class.java).putExtra(EXTRA_VERSION, update.version),
                // Mutable: the installer adds the status extras.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
    }

    private fun httpGet(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Droidtosh-Android/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw UpdateHttpException(code)
        }
        return connection
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    internal const val EXTRA_VERSION = "${BuildConfig.APPLICATION_ID}.extra.UPDATE_VERSION"
}


/** Receives the PackageInstaller session result for an update. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            // The new version replaces this process, so success has nothing left to do.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updates.onInstallAborted()
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Updates.onInstallFailed(installFailure(status, detail))
            }
        }
    }

    private fun installFailure(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT,
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
        -> "This update is signed differently from the installed app; reinstall Droidtosh from the release page"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage to install the update"
        else -> detail?.takeIf { it.isNotBlank() } ?: "The update couldn't be installed"
    }
}
