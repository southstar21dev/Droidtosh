package dev.southstar21.droidtosh.ui

import androidx.activity.BackEventCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class BackMotionTest {
    @Test fun leftEdgeSwipePushesRight() {
        assertEquals(12f, BackMotion.translationX(BackEventCompat.EDGE_LEFT, 0.5f, 24f), 0.001f)
    }

    @Test fun rightEdgeSwipePushesLeft() {
        assertEquals(-12f, BackMotion.translationX(BackEventCompat.EDGE_RIGHT, 0.5f, 24f), 0.001f)
    }

    @Test fun backKeyLeavesToTheRight() {
        assertEquals(1, BackMotion.direction(null))
        assertEquals(100, BackMotion.exitOffset(null, 100))
    }

    @Test fun exitFollowsTheEdge() {
        assertEquals(100, BackMotion.exitOffset(BackEventCompat.EDGE_LEFT, 100))
        assertEquals(-100, BackMotion.exitOffset(BackEventCompat.EDGE_RIGHT, 100))
    }

    @Test fun progressIsClampedAndScaleShrinksToNinetyPercent() {
        assertEquals(24f, BackMotion.translationX(BackEventCompat.EDGE_LEFT, 2f, 24f), 0.001f)
        assertEquals(1f, BackMotion.scale(0f), 0.001f)
        assertEquals(0.9f, BackMotion.scale(1f), 0.001f)
        assertEquals(0.9f, BackMotion.scale(5f), 0.001f)
    }
}
