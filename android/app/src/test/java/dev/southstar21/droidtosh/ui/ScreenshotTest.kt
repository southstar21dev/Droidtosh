package dev.southstar21.droidtosh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import dev.southstar21.droidtosh.ui.components.Tether
import dev.southstar21.droidtosh.ui.components.TetherState
import dev.southstar21.droidtosh.ui.theme.DroidtoshTheme
import dev.southstar21.droidtosh.update.UpdateState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot tests for every main screen, light and dark, with [FakeUi] state. They always run
 * (so a crash in any screen fails the build); images are only written by
 * `./gradlew recordRoborazziDebug`, into build/outputs/roborazzi/.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = PHONE)
class ScreenshotTest(private val dark: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    private fun snap(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            DroidtoshTheme(darkTheme = dark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    content()
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$OUTPUT/${name}_${if (dark) "dark" else "light"}.png")
    }

    private fun shell(name: String, ui: DroidUiState, destination: Destination = Destination.Home, page: Page? = null) = snap(name) {
        MainScaffold(
            ui = ui,
            actions = AppActions(),
            destination = destination,
            onDestinationChange = {},
            page = page,
            onPageChange = {},
            diagnostics = { DiagnosticsScreen(FakeUi.diagnostics, AppActions(), onBack = {}) },
        )
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun home_connected() = shell("home", FakeUi.connected)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun home_disconnected() = shell("home_disconnected", FakeUi.disconnected)

    @Test
    fun home_connecting() = shell("home_connecting", FakeUi.connecting)

    @Test
    fun home_ringing() = shell("home_ringing", FakeUi.ringing)

    @Test
    @Config(qualifiers = TABLET)
    fun home_tablet() = shell("home_tablet", FakeUi.connected)

    @Test
    @Config(qualifiers = PHONE_TALL, fontScale = 1.5f)
    fun home_large_font() = shell("home_font150", FakeUi.connected)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun transfers() = shell("files", FakeUi.transfers, Destination.Transfers)

    @Test
    fun transfers_empty() = shell("files_empty", FakeUi.noTransfers, Destination.Transfers)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun notifications() = shell("notifications", FakeUi.settingsNeedsWork, Destination.Notifications)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun calls() = shell("calls", FakeUi.connected, Destination.Calls)

    @Test
    @Config(qualifiers = PHONE_XTALL)
    fun connection() = shell("connection", FakeUi.settingsNeedsWork, page = Page.Connection)

    @Test
    fun connection_unpair_dialog() = snap("connection_unpair") {
        ConnectionScreen(FakeUi.connected, AppActions(), onBack = {}, onOpenDiagnostics = {}, initialUnpairDialog = true)
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun settings() = shell("settings", FakeUi.connected, page = Page.Settings)

    /** Settings › Updates in its main states: an offer, a download, a failed install, and up to date. */
    @Test
    @Config(qualifiers = PHONE_TALL)
    fun settings_updates() = snap("settings_updates") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            UpdatesGroup(FakeUi.connected, AppActions())
            UpdatesGroup(FakeUi.connected.copy(update = UpdateState.Downloading("1.5.0", 0.42f), betaUpdates = true), AppActions())
            UpdatesGroup(
                FakeUi.connected.copy(
                    update = UpdateState.Failed("The download didn't match its checksum", UpdateState.Available("1.5.0", 340)),
                ),
                AppActions(),
            )
            UpdatesGroup(FakeUi.connected.copy(update = UpdateState.UpToDate), AppActions())
        }
    }

    @Test
    @Config(qualifiers = PHONE_XTALL)
    fun diagnostics() = snap("diagnostics") {
        DiagnosticsScreen(FakeUi.diagnostics, AppActions(), onBack = {})
    }

    @Test
    fun onboarding_scan() = snap("onboarding_1_scan") {
        OnboardingContent(0, PermissionStatus(), repairNeeded = false, onRequestPermissions = {}, onOpenNotificationAccess = {}, onScan = {}, onFinish = {})
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun onboarding_repair() = snap("onboarding_1_scan_repair") {
        OnboardingContent(0, PermissionStatus(), repairNeeded = true, onRequestPermissions = {}, onOpenNotificationAccess = {}, onScan = {}, onFinish = {})
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun onboarding_permissions() = snap("onboarding_2_permissions") {
        OnboardingContent(1, FakeUi.onboardingPermissions, repairNeeded = false, onRequestPermissions = {}, onOpenNotificationAccess = {}, onScan = {}, onFinish = {})
    }

    @Test
    fun find_phone() = snap("find_phone") {
        FindPhoneScreen(onStop = {}, animate = false)
    }

    @Test
    fun share_sheet() = snap("share_sheet") {
        ShareSheet(
            ShareResult(queued = 5, total = 5, names = listOf("IMG_2048.HEIC", "IMG_2049.HEIC", "Trip notes.pdf", "a.txt", "b.txt")),
            onDone = {},
            onOpenTransfers = {},
            animateIn = false,
        )
    }

    @Test
    fun share_sheet_confirm() = snap("share_sheet_confirm") {
        ShareSheet(
            ShareResult(queued = 2, total = 2, names = listOf("IMG_2048.HEIC", "Trip notes.pdf")),
            onDone = {},
            onOpenTransfers = {},
            animateIn = false,
            onSend = {},
        )
    }

    @Test
    fun share_sheet_failed() = snap("share_sheet_failed") {
        ShareSheet(ShareResult(queued = 0, total = 1, names = emptyList()), onDone = {}, onOpenTransfers = {}, animateIn = false)
    }

    /** Every Tether state, large and small, so a drawing regression shows up at a glance. */
    @Test
    fun tether_states() = snap("tether_states") {
        val states = listOf(
            "Linked" to TetherState.Linked,
            "Seeking" to TetherState.Seeking,
            "Handshake" to TetherState.Handshake,
            "Streaming" to TetherState.Streaming(toPhone = true),
            "Mirroring" to TetherState.Mirroring,
            "Ringing" to TetherState.Ringing,
            "Echo" to TetherState.Echo,
            "Resting" to TetherState.Resting,
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            states.forEach { (name, state) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Tether(state = state, size = 72.dp)
                    Tether(state = state, size = 20.dp)
                    Text(name, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "dark={0}")
        fun themes(): List<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
    }
}

private const val OUTPUT = "build/outputs/roborazzi"
private const val PHONE = "w411dp-h891dp-xxhdpi"
private const val PHONE_TALL = "w411dp-h1500dp-xxhdpi"
private const val PHONE_XTALL = "w411dp-h2300dp-xxhdpi"
private const val TABLET = "w1024dp-h768dp-land-xhdpi"
