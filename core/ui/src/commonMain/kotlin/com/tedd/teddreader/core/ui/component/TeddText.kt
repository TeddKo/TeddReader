package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * The app's only way to put text on screen, and the reason a screen never reaches for Material's own
 * `Text`.
 *
 * Built on `BasicText` from `foundation` rather than wrapping the Material composable, so no screen
 * needs a `material3` import to render a label. That is what lets the rule "no `material3` outside
 * `core/ui`" be enforced by the `checkMaterial3Imports` verification task rather than by a reviewer
 * noticing a stray import: with Material's `Text` in reach, every screen would need the import the
 * gate forbids, and the gate could not exist. Reaching the type scale through [teddReaderTypography]
 * here also means the default is always a real app style rather than an inherited accident.
 *
 * The default style is the body style, not an inherited ambient one. Material's `Text` merges
 * `LocalTextStyle`, which lets a parent silently restyle text several layers down; making the style an
 * explicit parameter with a named default means what a label looks like is decided where the label is
 * written.
 *
 * Colour is the one thing still inherited, through Material's `LocalContentColor`. That is deliberate:
 * the sheets, menus, sliders and text fields this app keeps from Material all publish their content
 * colour through that local, so a label inside one of them has to read it or it would fight its own
 * container.
 *
 * @param text The string to render.
 * @param modifier Modifier applied to the text's layout node.
 * @param style The type style to draw with; defaults to the app's body style. Pass a style from
 * [teddReaderTypography] rather than constructing one, so the scale stays the single source of truth.
 * @param color The text colour. [Color.Unspecified], the default, means inherit the surrounding
 * content colour, which is what a label inside a themed container wants.
 * @param maxLines The line count at which text stops; combined with [overflow] this is what decides
 * whether a long title truncates or pushes its row taller.
 * @param overflow How text that exceeds [maxLines] is treated; clipping by default so a caller opts
 * into an ellipsis deliberately.
 * @param textAlign Horizontal alignment within the text's own width; null keeps whatever [style]
 * already specifies rather than overriding it with a default.
 */
@Composable
fun TeddText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = teddReaderTypography().bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val coloredStyle = style.copy(color = resolvedColor)
    val resolvedStyle = if (textAlign == null) coloredStyle else coloredStyle.copy(textAlign = textAlign)

    BasicText(
        text = text,
        modifier = modifier,
        style = resolvedStyle,
        overflow = overflow,
        maxLines = maxLines,
    )
}

/** Compose preview rendering [TeddText] at its default body style and at a title style. */
@Preview
@Composable
private fun TeddTextPreview() {
    TeddPreviewSurface {
        TeddText(text = "Recent documents", style = teddReaderTypography().titleMedium)
        TeddText(text = "Twelve files, last opened yesterday.")
    }
}
