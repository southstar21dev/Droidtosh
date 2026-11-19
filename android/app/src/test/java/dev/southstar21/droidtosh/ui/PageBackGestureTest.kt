package dev.southstar21.droidtosh.ui

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.DpRect
import dev.southstar21.droidtosh.ui.theme.DroidtoshTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the predictive-back callbacks into the page overlay and checks where the page goes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PageBackGestureTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var page by mutableStateOf<Page?>(Page.Diagnostics)

    private fun show() {
        compose.setContent {
            DroidtoshTheme {
                MainScaffold(
                    ui = FakeUi.connected,
                    actions = AppActions(),
                    destination = Destination.Home,
                    onDestinationChange = {},
                    page = page,
                    onPageChange = { page = it },
                    diagnostics = { Box(Modifier.fillMaxSize().testTag(PAGE)) },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun pageBounds(): DpRect = compose.onNodeWithTag(PAGE).getBoundsInRoot()

    private fun drag(edge: Int, progress: Float) {
        val dispatcher = compose.activity.onBackPressedDispatcher
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, edge))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(0f, 0f, progress, edge))
        }
        compose.waitForIdle()
    }

    private fun DpRect.centerX() = (left + right).value / 2f
    private fun DpRect.w() = (right - left).value

    @Test
    fun leftEdgeSwipeMovesThePageRightAndShrinksIt() {
        show()
        val rest = pageBounds()
        drag(BackEventCompat.EDGE_LEFT, 1f)
        val dragged = pageBounds()
        assertTrue("page should move right, $rest -> $dragged", dragged.centerX() > rest.centerX())
        assertTrue("page should shrink", dragged.w() < rest.w())
    }

    @Test
    fun rightEdgeSwipeMovesThePageLeft() {
        show()
        val rest = pageBounds()
        drag(BackEventCompat.EDGE_RIGHT, 1f)
        val dragged = pageBounds()
        assertTrue("page should move left, $rest -> $dragged", dragged.centerX() < rest.centerX())
        assertTrue("page should shrink", dragged.w() < rest.w())
    }

    @Test
    fun cancelledSwipeSpringsBackToFullSize() {
        show()
        val rest = pageBounds()
        drag(BackEventCompat.EDGE_LEFT, 0.8f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertBoundsEqual(rest, pageBounds())
        assertEquals(Page.Diagnostics, page)
    }

    @Test
    fun committedSwipeFromDiagnosticsLeavesConnectionAtFullSize() {
        show()
        val root = compose.onRoot().getBoundsInRoot()
        drag(BackEventCompat.EDGE_LEFT, 1f)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(Page.Connection, page)
        // The overlay is still open on Connection, so a fresh Diagnostics page must fill the screen again.
        page = Page.Diagnostics
        compose.waitForIdle()
        assertBoundsEqual(root, pageBounds())
    }

    private fun assertBoundsEqual(expected: DpRect, actual: DpRect) {
        val e = Rect(expected.left.value, expected.top.value, expected.right.value, expected.bottom.value)
        val a = Rect(actual.left.value, actual.top.value, actual.right.value, actual.bottom.value)
        assertEquals(e.left, a.left, 0.5f)
        assertEquals(e.top, a.top, 0.5f)
        assertEquals(e.right, a.right, 0.5f)
        assertEquals(e.bottom, a.bottom, 0.5f)
    }

    private companion object {
        const val PAGE = "page"
    }
}
