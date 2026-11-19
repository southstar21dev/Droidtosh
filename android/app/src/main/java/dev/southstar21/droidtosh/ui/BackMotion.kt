package dev.southstar21.droidtosh.ui

import androidx.activity.BackEventCompat

/** Pure direction and size maths for the predictive-back animation of a full-screen page. */
internal object BackMotion {
    const val MIN_SCALE = 0.9f

    /**
     * +1 when the surface travels toward the right, -1 toward the left. Like stock Android the surface
     * follows the finger: a swipe from the left edge pushes it right, from the right edge pushes it left.
     * Anything that is not a gesture (back key, 3-button navigation) has no edge and leaves the way the
     * page came in, to the right.
     */
    fun direction(swipeEdge: Int?): Int = when (swipeEdge) {
        BackEventCompat.EDGE_RIGHT -> -1
        else -> 1
    }

    /** Horizontal shift of the page while dragging, [progress] in 0..1, capped at [maxShiftPx]. */
    fun translationX(swipeEdge: Int?, progress: Float, maxShiftPx: Float): Float =
        direction(swipeEdge) * maxShiftPx * progress.coerceIn(0f, 1f)

    fun scale(progress: Float): Float = 1f - (1f - MIN_SCALE) * progress.coerceIn(0f, 1f)

    /** Target offset of the exit slide for a surface [fullWidth] wide, leaving the way [swipeEdge] pushed it. */
    fun exitOffset(swipeEdge: Int?, fullWidth: Int): Int = direction(swipeEdge) * fullWidth
}
