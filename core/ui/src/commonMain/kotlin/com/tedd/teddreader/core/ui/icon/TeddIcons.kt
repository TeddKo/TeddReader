package com.tedd.teddreader.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Hand-drawn [ImageVector]s for the reader chrome, in place of Material's icon library. This app
 * does not depend on `material-icons-extended` (a large artifact for the handful of glyphs actually
 * needed), so each icon here is a minimal path definition covering exactly the reader's controls
 * (navigation, bookmarking, playback, battery, delete). Every path is filled or stroked with
 * `Color.Black` regardless of theme; that value is not the rendered color — Material's `Icon`
 * composable recolors the whole vector via its `tint` color filter (default
 * `LocalContentColor.current`), which replaces every opaque pixel's color and only preserves alpha,
 * so black here is just a convenient, high-contrast placeholder for "the shape," not the on-screen
 * color.
 */
object TeddIcons {
    /** A left-pointing chevron, used for back/up navigation. */
    val Back: ImageVector by lazy {
        ImageVector.Builder(
            name = "Back",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 12f)
                lineTo(5f, 12f)
                moveTo(11f, 6f)
                lineTo(5f, 12f)
                lineTo(11f, 18f)
            }
        }.build()
    }

    /** An open bookmark ribbon, for a page that is not currently saved. */
    val BookmarkOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "BookmarkOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 4f)
                lineTo(17f, 4f)
                lineTo(17f, 20f)
                lineTo(12f, 16.5f)
                lineTo(7f, 20f)
                close()
            }
        }.build()
    }

    /** A filled bookmark ribbon, for a page that is currently saved. */
    val BookmarkFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "BookmarkFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(7f, 4f)
                lineTo(17f, 4f)
                lineTo(17f, 20f)
                lineTo(12f, 16.5f)
                lineTo(7f, 20f)
                close()
            }
        }.build()
    }

    /** An "X" glyph, used to dismiss a sheet, dialog, or overlay. */
    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 6f)
                lineTo(18f, 18f)
                moveTo(18f, 6f)
                lineTo(6f, 18f)
            }
        }.build()
    }

    /** Three vertically stacked dots, opening an overflow menu of secondary actions. */
    val MoreVert: ImageVector by lazy {
        ImageVector.Builder(
            name = "MoreVert",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            repeat(3) { index ->
                val centerY = 6f + index * 6f
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                    moveTo(12f, centerY - 1.6f)
                    curveTo(13.1f, centerY - 1.6f, 14f, centerY - 0.7f, 14f, centerY + 0.4f)
                    curveTo(14f, centerY + 1.5f, 13.1f, centerY + 2.4f, 12f, centerY + 2.4f)
                    curveTo(10.9f, centerY + 2.4f, 10f, centerY + 1.5f, 10f, centerY + 0.4f)
                    curveTo(10f, centerY - 0.7f, 10.9f, centerY - 1.6f, 12f, centerY - 1.6f)
                    close()
                }
            }
        }.build()
    }

    /** A left-pointing caret, for moving to the previous page or track. */
    val Previous: ImageVector by lazy {
        ImageVector.Builder(
            name = "Previous",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15f, 6f)
                lineTo(9f, 12f)
                lineTo(15f, 18f)
            }
        }.build()
    }

    /** A right-pointing caret, for moving to the next page or track. */
    val Next: ImageVector by lazy {
        ImageVector.Builder(
            name = "Next",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 6f)
                lineTo(15f, 12f)
                lineTo(9f, 18f)
            }
        }.build()
    }

    /** A right-pointing triangle, starting playback (e.g. auto-scroll). */
    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "Play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(8f, 6f)
                lineTo(18f, 12f)
                lineTo(8f, 18f)
                close()
            }
        }.build()
    }

    /** Two vertical bars, pausing playback (e.g. auto-scroll). */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(8f, 6f)
                lineTo(11f, 6f)
                lineTo(11f, 18f)
                lineTo(8f, 18f)
                close()
                moveTo(13f, 6f)
                lineTo(16f, 6f)
                lineTo(16f, 18f)
                lineTo(13f, 18f)
                close()
            }
        }.build()
    }

    /** A battery outline with a terminal nub, for the reader's own battery-percentage indicator. */
    val Battery: ImageVector by lazy {
        ImageVector.Builder(
            name = "Battery",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 7f)
                lineTo(18f, 7f)
                lineTo(18f, 17f)
                lineTo(5f, 17f)
                close()
                moveTo(21f, 10f)
                lineTo(21f, 14f)
            }
        }.build()
    }

    /** A trash can, for a destructive delete/remove action. */
    val Delete: ImageVector by lazy {
        ImageVector.Builder(
            name = "Delete",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 7f)
                lineTo(20f, 7f)
                moveTo(9f, 7f)
                lineTo(9f, 4f)
                lineTo(15f, 4f)
                lineTo(15f, 7f)
                moveTo(7f, 7f)
                lineTo(8f, 20f)
                lineTo(16f, 20f)
                lineTo(17f, 7f)
            }
        }.build()
    }
}
