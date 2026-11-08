package dev.southstar21.droidtosh.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few glyphs the UI needs beyond material-icons-core, as 24 dp Material-style vectors. Kept
 * here instead of pulling in material-icons-extended, which would add megabytes to the
 * unminified APK.
 */
object DroidIcons {
    val Laptop by lazy {
        icon(
            "Laptop",
            "M20,18c1.1,0 1.99,-0.9 1.99,-2L22,6c0,-1.1 -0.9,-2 -2,-2H4c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2H0v2h24v-2h-4zM4,6h16v10H4V6z",
        )
    }
    val Folder by lazy {
        icon(
            "Folder",
            "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
        )
    }
    val File by lazy {
        icon(
            "File",
            "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2zM16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
        )
    }
    val Clipboard by lazy {
        icon(
            "Clipboard",
            "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2H5c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20H5V4h2v3h10V4h2v16z",
        )
    }
    val Transfers by lazy {
        icon(
            "Transfers",
            "M16,17.01V10h-2v7.01h-3L15,21l4,-3.99h-3zM9,3L5,6.99h3V14h2V6.99h3L9,3z",
        )
    }
    val Battery by lazy {
        icon(
            "Battery",
            "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4z",
        )
    }
    val Upload by lazy { icon("Upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z") }
    val Download by lazy { icon("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z") }
    val Ring by lazy {
        icon(
            "Ring",
            "M0,15h2V9H0v6zM3,17h2V7H3v10zM22,9v6h2V9h-2zM19,17h2V7h-2v10zM16.5,3h-9C6.67,3 6,3.67 6,4.5v15c0,0.83 0.67,1.5 1.5,1.5h9c0.83,0 1.5,-0.67 1.5,-1.5v-15c0,-0.83 -0.67,-1.5 -1.5,-1.5zM16,19H8V5h8v14z",
        )
    }
    val Pulse by lazy {
        icon(
            "Pulse",
            "M23,8c0,1.1 -0.9,2 -2,2c-0.18,0 -0.35,-0.02 -0.51,-0.07l-3.56,3.55C16.98,13.64 17,13.82 17,14c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2c0,-0.18 0.02,-0.36 0.07,-0.52l-2.55,-2.55C10.36,10.98 10.18,11 10,11s-0.36,-0.02 -0.52,-0.07l-4.55,4.56C4.98,15.65 5,15.82 5,16c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2s0.9,-2 2,-2c0.18,0 0.35,0.02 0.51,0.07l4.56,-4.55C8.02,9.36 8,9.18 8,9c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,0.18 -0.02,0.36 -0.07,0.52l2.55,2.55C14.64,12.02 14.82,12 15,12s0.36,0.02 0.52,0.07l3.55,-3.56C19.02,8.35 19,8.18 19,8c0,-1.1 0.9,-2 2,-2s2,0.9 2,2z",
        )
    }
    val Cloud by lazy {
        icon(
            "Cloud",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4 9.11,4 6.6,5.64 5.35,8.04 2.34,8.36 0,10.91 0,14c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5 0,-2.64 -2.05,-4.78 -4.65,-4.96z",
        )
    }
    val Wifi by lazy {
        icon(
            "Wifi",
            "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9zM9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0zM5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
        )
    }
    val Clock by lazy {
        icon(
            "Clock",
            "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2zM12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8zM12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
        )
    }
    val Fullscreen by lazy {
        icon("Fullscreen", "M7,14H5v5h5v-2H7v-3zM5,10h2V7h3V5H5v5zM17,17h-3v2h5v-5h-2v3zM14,5v2h3v3h2V5h-5z")
    }
    val Copy by lazy {
        icon(
            "Copy",
            "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
        )
    }
    val History by lazy {
        icon(
            "History",
            "M13,3c-4.97,0 -9,4.03 -9,9H1l3.89,3.89 0.07,0.14L9,12H6c0,-3.87 3.13,-7 7,-7s7,3.13 7,7 -3.13,7 -7,7c-1.93,0 -3.68,-0.79 -4.94,-2.06l-1.42,1.42C8.27,19.99 10.51,21 13,21c4.97,0 9,-4.03 9,-9s-4.03,-9 -9,-9zM12,8v5l4.28,2.54 0.72,-1.21 -3.5,-2.08V8H12z",
        )
    }
    val QrCode by lazy {
        icon(
            "QrCode",
            "M3,3h7v7H3V3zM5,5v3h3V5H5zM14,3h7v7h-7V3zM16,5v3h3V5h-3zM3,14h7v7H3v-7zM5,16v3h3v-3H5zM14,14h2v2h-2zM18,14h3v2h-3zM14,18h2v3h-2zM17,17h2v2h-2zM19,19h2v2h-2z",
        )
    }
    val Spark by lazy {
        icon("Spark", "M12,2l2.1,6.9L21,11l-6.9,2.1L12,20l-2.1,-6.9L3,11l6.9,-2.1z")
    }
    val Shield by lazy {
        icon(
            "Shield",
            "M12,1L3,5v6c0,5.55 3.84,10.74 9,12 5.16,-1.26 9,-6.45 9,-12V5l-9,-4zM10,17l-4,-4 1.41,-1.41L10,14.17l6.59,-6.59L18,9l-8,8z",
        )
    }
    val Speaker by lazy {
        icon(
            "Speaker",
            "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = addPathNodes(pathData),
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.NonZero,
        ).build()
}
