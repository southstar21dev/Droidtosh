package dev.southstar21.droidtosh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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

    private fun shell(ui: DroidUiState, destination: Destination = Destination.Home) = snap(
        when (destination) {
            Destination.Home -> "home"
            Destination.Transfers -> "transfers"
            Destination.Settings -> "settings"
        },
    ) {
        MainScaffold(
            ui = ui,
            actions = AppActions(),
            destination = destination,
            onDestinationChange = {},
            showDiagnostics = false,
            onShowDiagnosticsChange = {},
            diagnostics = {},
        )
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun home_connected() = shell(FakeUi.connected)

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun home_disconnected() = snap("home_disconnected") {
        MainScaffold(FakeUi.disconnected, AppActions(), Destination.Home, {}, false, {}, {})
    }

    @Test
    fun home_connecting() = snap("home_connecting") {
        MainScaffold(FakeUi.connecting, AppActions(), Destination.Home, {}, false, {}, {})
    }

    @Test
    fun home_ringing() = snap("home_ringing") {
        MainScaffold(FakeUi.ringing, AppActions(), Destination.Home, {}, false, {}, {})
    }

    @Test
    @Config(qualifiers = TABLET)
    fun home_tablet() = snap("home_tablet") {
        MainScaffold(FakeUi.connected, AppActions(), Destination.Home, {}, false, {}, {})
    }

    @Test
    @Config(qualifiers = PHONE_TALL, fontScale = 1.5f)
    fun home_large_font() = snap("home_font150") {
        MainScaffold(FakeUi.connected, AppActions(), Destination.Home, {}, false, {}, {})
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun transfers() = shell(FakeUi.transfers, Destination.Transfers)

    @Test
    fun transfers_empty() = snap("transfers_empty") {
        MainScaffold(FakeUi.noTransfers, AppActions(), Destination.Transfers, {}, false, {}, {})
    }

    @Test
    @Config(qualifiers = PHONE_XTALL)
    fun settings() = shell(FakeUi.settingsNeedsWork, Destination.Settings)

    @Test
    fun settings_unpair_dialog() = snap("settings_unpair") {
        SettingsScreen(FakeUi.connected, AppActions(), onOpenDiagnostics = {}, initialUnpairDialog = true)
    }

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
    fun onboarding_welcome() = snap("onboarding_1_welcome") {
        OnboardingContent(0, {}, PermissionStatus(), repairNeeded = false, onRequestPermissions = {}, onOpenNotificationAccess = {}, onScan = {})
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun onboarding_permissions() = snap("onboarding_2_permissions") {
        OnboardingContent(1, {}, FakeUi.onboardingPermissions, repairNeeded = false, onRequestPermissions = {}, onOpenNotificationAccess = {}, onScan = {})
    }

    @Test
    fun onboarding_pair() = snap("onboarding_3_pair") {
        OnboardingContent(2, {}, PermissionStatus(), repairNeeded = false, onRequestPermissions = {}, onOpenNotificationAccess = {}, onScan = {})
    }

    @Test
    @Config(qualifiers = PHONE_TALL)
    fun onboarding_repair() = snap("onboarding_3_pair_repair") {
        OnboardingContent(2, {}, PermissionStatus(), repairNeeded = true, onRequestPermissions = {}, onOpenNotificationAccess = {}, onScan = {})
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
    fun share_sheet_failed() = snap("share_sheet_failed") {
        ShareSheet(ShareResult(queued = 0, total = 1, names = emptyList()), onDone = {}, onOpenTransfers = {}, animateIn = false)
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
