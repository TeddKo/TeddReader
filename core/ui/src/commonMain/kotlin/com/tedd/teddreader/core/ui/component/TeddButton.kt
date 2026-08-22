package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * Visual weight of a [TeddButton], chosen by how much attention the action deserves rather than by
 * which Material button type backs it. A screen picks one value per action instead of reaching for
 * `Button`/`OutlinedButton`/`TextButton` directly, so emphasis stays a design decision rather than a
 * component choice made at each call site.
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
 * The app's single button surface: one composable that switches between Material's `Button`,
 * `OutlinedButton`, and `TextButton` depending on [emphasis], so every screen gets the same minimum
 * touch target, corner shape, and label style no matter which Material button type ends up backing
 * it. Without this wrapper, every call site would have to remember on its own to pin a 48dp minimum
 * height, swap in the app's shape scale, and flatten the primary button's elevation to zero.
 *
 * @param text The button's label, rendered with [teddReaderTypography]'s `labelLarge` style.
 * @param onClick Invoked when the button is tapped; never invoked while [enabled] is false.
 * @param modifier Modifier applied to the underlying Material button, after the enforced 48dp
 * minimum height is applied.
 * @param enabled Whether the button responds to taps; false also switches it to its disabled colors.
 * @param emphasis Which visual treatment to render — see [TeddButtonEmphasis].
 * @param contentPadding Padding between the button's edge and its label.
 */
@Composable
fun TeddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: TeddButtonEmphasis = TeddButtonEmphasis.Primary,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.large,
        vertical = DefaultTeddReaderSpacing.small,
    ),
) {
    val typography = teddReaderTypography()
    val shapedModifier = modifier.defaultMinSize(minHeight = 48.dp)
    val shape = teddReaderShapes().medium
    val label: @Composable () -> Unit = {
        Text(text = text, style = typography.labelLarge)
    }

    when (emphasis) {
        TeddButtonEmphasis.Primary -> Button(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = contentPadding,
            content = { label() },
        )

        TeddButtonEmphasis.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = contentPadding,
            content = { label() },
        )

        TeddButtonEmphasis.Text -> TextButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(),
            content = { label() },
        )

        TeddButtonEmphasis.Destructive -> TextButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
            ),
            content = { label() },
        )
    }
}

/**
 * A pill-shaped tag used for filters and inline labels. Renders as a tappable [Surface] when
 * [onClick] is supplied; when it is not, the same border, background, and pill shape are drawn by
 * hand on plain [Text] instead, because a non-clickable [Surface] would still take part in
 * touch/semantics handling that a purely informational chip should not. [selected] swaps the
 * fill/content colors to the secondary-container pair, and on the non-interactive path also marks
 * the semantics as selected so accessibility services announce the state that would otherwise come
 * for free from [Surface]'s own `selected` parameter.
 *
 * @param text The chip's label, shown on a single line with ellipsis truncation.
 * @param onClick Invoked when the chip is tapped; when null, the chip renders as static, unclickable
 * text instead of a [Surface].
 * @param modifier Modifier applied to the chip's root.
 * @param enabled Whether the chip responds to taps; only meaningful when [onClick] is non-null.
 * @param selected Whether the chip is drawn in its selected (secondary-container) colors.
 * @param contentPadding Padding between the chip's edge and its label.
 */
@Composable
fun TeddChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.medium,
        vertical = DefaultTeddReaderSpacing.xSmall,
    ),
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(percent = 50)

    if (onClick != null) {
        Surface(
            selected = selected,
            onClick = onClick,
            modifier = modifier.semantics {
                role = Role.Button
            },
            enabled = enabled,
            shape = shape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(contentPadding),
                style = teddReaderTypography().labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = text,
            modifier = (if (selected) modifier.semantics { this.selected = true } else modifier)
                .clip(shape)
                .background(backgroundColor)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .padding(contentPadding),
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
