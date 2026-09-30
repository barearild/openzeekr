package com.openzeekr.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * App-local vector icons where Material's stock glyph is misleading. Tinted by the caller's
 * [androidx.compose.material3.Icon] like any Material icon (a ColorFilter recolours the whole vector,
 * so stroke colour here is just a placeholder).
 */
object OzIcons {

    /**
     * A car side window (issue #11). Material's `Icons.Filled.Window` is a 2x2 pane grid that reads as
     * the Microsoft Windows logo on the window control tile; this is a car door + glass profile with an
     * A-pillar and sill so it clearly means "car window". Stroked to match the line-art feel of the tile.
     */
    val CarWindow: ImageVector by lazy {
        ImageVector.Builder(
            name = "CarWindow",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            val ink = SolidColor(Color(0xFF000000))
            // Door outline (rounded rectangle).
            path(
                stroke = ink, strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 6.5f)
                lineTo(20f, 6.5f)
                lineTo(20f, 17.5f)
                lineTo(4f, 17.5f)
                close()
            }
            // Window sill: the horizontal line the glass drops behind.
            path(
                stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(5.5f, 13.2f)
                lineTo(18.5f, 13.2f)
            }
            // A-pillar diagonal: turns the top pane into a side-window shape (not a 2x2 grid).
            path(
                stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(9f, 6.5f)
                lineTo(7.2f, 13.2f)
            }
            // Door handle.
            path(
                stroke = ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(14.5f, 15.4f)
                lineTo(16.8f, 15.4f)
            }
        }.build()
    }
}
