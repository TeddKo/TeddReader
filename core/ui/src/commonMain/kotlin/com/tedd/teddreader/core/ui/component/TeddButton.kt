package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.teddClickable
import com.tedd.teddreader.core.ui.extension.teddSelectable

/**
 * Visual weight of a [TeddButton], chosen by how much attention the action deserves rather than by
 * which background/border/content combination backs it. A screen picks one value per action instead
 * of hand-assembling that combination itself, so emphasis stays a design decision rather than a
 * choice repeated at each call site.
 */
enum class TeddButtonEmphasis {
    /** The filled, highest-weight action on a screen — solid background color. */
    Primary,

    /** A secondary action alongside a primary one — outlined, same shape and padding as [Primary]. */
    Secondary,

    /** A low-emphasis action with no border or fill, for dismissive or optional actions. */
    Text,

    /** A `Text`-styled action that signals an irreversible choice by rendering in the error color. */
    Destructive,
}

/**
 * The app's single button surface: a [TeddText] label built directly on [teddClickable] instead of
 * Material's `Button`/`OutlinedButton`/`TextButton`. Every one of those three derives its ripple's
 * colour from the button's own content colour, which is exactly what this app's ripple policy forbids
 * — a consistent tactile colour across the whole app, regardless of which surface is pressed (see
 * [teddClickable]). Building the button by hand is the only way to keep that one ripple colour while
 * still letting each [emphasis] choose its own background, border, and content colour. The touch
 * target, corner shape, and label style are pinned once here so no [emphasis] branch can drift from
 * the others by accident.
 *
 * The label is the button's root instead of sitting inside a `Row` or `Box` kept only to center it:
 * with one child and no overlay, the alignment such a wrapper would give for free is exactly what the
 * label's own modifier and parameters already express — `TextAlign.Center` centers it horizontally,
 * and `Modifier.wrapContentHeight(Alignment.CenterVertically)` centers it vertically — so a second
 * layout node buys nothing. That vertical centering has to be the innermost modifier, applied after
 * `padding`, because `defaultMinSize` only ever raises the *constraint* handed to whatever it wraps;
 * it does not itself center a child that ends up smaller than that minimum. Left off, a short label
 * would sit at the top of the 48dp touch target instead of its middle. `background`, the border, and
 * [teddClickable] all sit further out in the chain than `padding`, so the fill, the outline, and the
 * ripple all cover the full enforced height instead of shrinking down to the label's own natural size.
 *
 * @param text The button's label, rendered with [teddReaderTypography]'s `labelLarge` style.
 * @param onClick Invoked when the button is tapped; never invoked while [enabled] is false.
 * @param modifier Modifier applied to the button's root, before the enforced 48dp minimum height.
 * @param enabled Whether the button responds to taps; false also switches it to its disabled colors.
 * @param emphasis Which visual treatment to render — see [TeddButtonEmphasis].
 * @param contentPadding Padding between the button's edge and its label; null means the theme's
 * large/small combination is used.
 */
@Composable
fun TeddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: TeddButtonEmphasis = TeddButtonEmphasis.Primary,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.large,
        vertical = spacing.small,
    )
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val shape = teddReaderShapes().medium

    val backgroundColor: Color
    val contentColor: Color
    val borderColor: Color?

    when (emphasis) {
        TeddButtonEmphasis.Primary -> {
            backgroundColor = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.1f)
            contentColor = if (enabled) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = 0.38f)
            borderColor = null
        }

        TeddButtonEmphasis.Secondary -> {
            backgroundColor = Color.Transparent
            contentColor = if (enabled) {
                colors.onSurfaceVariant
            } else {
                colors.onSurfaceVariant.copy(alpha = 0.38f)
            }
            borderColor = colors.outlineVariant
        }

        TeddButtonEmphasis.Text -> {
            backgroundColor = Color.Transparent
            contentColor = if (enabled) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.38f)
            borderColor = null
        }

        TeddButtonEmphasis.Destructive -> {
            backgroundColor = Color.Transparent
            contentColor = if (enabled) colors.error else colors.error.copy(alpha = 0.6f)
            borderColor = null
        }
    }

    TeddText(
        text = text,
        modifier = modifier
            .defaultMinSize(minHeight = spacing.touchTarget)
            .background(color = backgroundColor, shape = shape)
            .run { if (borderColor != null) border(BorderStroke(1.dp, borderColor), shape) else this }
            .teddClickable(onClick = onClick, shape = shape, enabled = enabled, role = Role.Button)
            .padding(resolvedContentPadding)
            .wrapContentHeight(Alignment.CenterVertically),
        style = typography.labelLarge,
        color = contentColor,
        textAlign = TextAlign.Center,
    )
}

/**
 * A pill-shaped tag used for filters and inline labels. Renders as a tappable pill built on
 * [teddSelectable] when [onClick] is supplied, rather than Material's `Surface(selected = ...)`: that
 * surface's ripple derives its colour from `contentColor` like every other Material clickable, which
 * this app's ripple policy forbids (see [teddClickable]), and its own minimum-touch-target handling
 * grows the visible pill along with the tap target, which would misalign a compact filter row.
 *
 * `minimumInteractiveComponentSize` reserves the 48dp touch floor without touching what is drawn, which
 * is the whole reason it sits outermost in the chain. Measured on the semantics tree: with it, the text
 * node stays 25dp — the pill's own height, a `labelLarge` line plus 4dp of padding either side — while
 * the node that owns the interaction measures 48dp square. Without it both collapse to 25dp. The
 * background, border and ripple all attach inside it, so they follow the pill rather than the floor:
 * the ripple stays clipped to the visible shape and the pill never inflates.
 *
 * Relying on Compose stretching the hit test on its own is not enough here. That fallback only applies
 * where no node claims the inbound touch, and it competes with siblings by distance — in the filter
 * rows these chips live in, spaced 8dp apart, adjacent chips split the gap between them and the
 * effective target lands well under 48dp. Reserving the space is what makes the floor real.
 *
 * When [onClick] is null, the same border, background, and pill
 * shape are drawn by hand on plain [TeddText] instead, because even a non-clickable interactive
 * modifier would still take part in touch/semantics handling that a purely informational chip should
 * not. [selected] swaps the fill/content colors to the secondary-container pair, and on the
 * non-interactive path also marks the semantics as selected so accessibility services announce the
 * state that would otherwise come for free from a selectable container's own `selected` parameter.
 *
 * @param text The chip's label, shown on a single line with ellipsis truncation.
 * @param onClick Invoked when the chip is tapped; when null, the chip renders as static, unclickable
 * text instead of a tappable pill.
 * @param modifier Modifier applied to the chip's root.
 * @param enabled Whether the chip responds to taps; only meaningful when [onClick] is non-null.
 * @param selected Whether the chip is drawn in its selected (secondary-container) colors.
 * @param contentPadding Padding between the chip's edge and its label; null means the theme's
 * medium/xSmall combination is used.
 */
@Composable
fun TeddChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.medium,
        vertical = spacing.xSmall,
    )
    val colors = teddReaderColors()
    val backgroundColor = if (selected) {
        colors.secondaryContainer
    } else {
        colors.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        colors.onSecondaryContainer
    } else {
        colors.onSurfaceVariant
    }
    val shape = RoundedCornerShape(percent = 50)

    if (onClick != null) {
        TeddText(
            text = text,
            modifier = modifier
                .minimumInteractiveComponentSize()
                .background(backgroundColor, shape)
                .border(BorderStroke(1.dp, colors.outlineVariant), shape)
                .teddSelectable(
                    selected = selected,
                    onClick = onClick,
                    shape = shape,
                    enabled = enabled,
                )
                .padding(resolvedContentPadding),
            color = contentColor,
            style = teddReaderTypography().labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        TeddText(
            text = text,
            modifier = (if (selected) modifier.semantics { this.selected = true } else modifier)
                .clip(shape)
                .background(backgroundColor)
                .border(BorderStroke(1.dp, colors.outlineVariant), shape)
                .padding(resolvedContentPadding),
            color = contentColor,
            style = teddReaderTypography().labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compose preview rendering [TeddButton] at its default (primary-emphasis) styling. */
@Preview
@Composable
private fun TeddButtonPreview() {
    TeddPreviewSurface {
        TeddButton(
            text = "Open document",
            onClick = {},
        )
    }
}
