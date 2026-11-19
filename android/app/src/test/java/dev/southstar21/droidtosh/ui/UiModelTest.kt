package dev.southstar21.droidtosh.ui

import dev.southstar21.droidtosh.update.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Test

class UiModelTest {
    private val granted = PermissionStatus(
        notifications = true,
        phone = true,
        notificationAccess = true,
    )

    @Test
    fun problemAndUpdateAreBothShown() {
        val ui = DroidUiState(
            permissions = granted,
            batteryOptimizationIgnored = false,
            update = UpdateState.ReadyToInstall("1.2.0"),
        )
        assertEquals(
            listOf(Attention.Problem(Issue.BatteryOptimization), Attention.Update("1.2.0")),
            ui.attentions,
        )
    }

    @Test
    fun failedInstallKeepsOfferingTheUpdate() {
        val ui = DroidUiState(
            permissions = granted,
            update = UpdateState.Failed("boom", UpdateState.Available("1.2.0", 12)),
        )
        assertEquals(listOf<Attention>(Attention.Update("1.2.0")), ui.attentions)
    }

    @Test
    fun nothingToShowWhenAllIsWell() {
        assertEquals(emptyList<Attention>(), DroidUiState(permissions = granted).attentions)
    }
}
