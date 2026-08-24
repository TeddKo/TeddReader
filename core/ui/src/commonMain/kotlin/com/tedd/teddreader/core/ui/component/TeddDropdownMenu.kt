package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * An overflow menu anchored to whatever it is composed inside, holding [TeddDropdownMenuItem] rows.
 *
 * Wraps Material's menu rather than rebuilding it, for the same reason [TeddAlertDialog] does: a menu
 * opens in a platform popup, positions itself so it stays on screen near its anchor, traps and then
 * restores focus, and dismisses on back or outside tap. Reimplementing that correctly buys nothing.
 * What this wrapper adds is the app's surface and shape in place of Material's, and it keeps the
 * Material import inside this module so a screen showing an overflow menu needs no `material3` import.
 *
 * The menu positions itself relative to its parent, so it belongs inside the same container as the
 * control that opens it — typically a `Box` that also holds the icon button.
 *
 * @param expanded Whether the menu is showing. The caller owns this state, because the control that
 * opens the menu lives outside the menu's own composition.
 * @param onDismissRequest Invoked when the user dismisses the menu without choosing — outside tap or
 * back gesture. Must clear whatever state [expanded] reads, or the menu cannot be reopened.
 * @param modifier Modifier applied to the menu's surface.
 * @param content The menu's rows, normally [TeddDropdownMenuItem] calls.
 */
@Composable
fun TeddDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = teddReaderColors().surfaceContainerLow,
        shape = teddReaderShapes().small,
        content = content,
    )
}

/**
 * A single row inside a [TeddDropdownMenu], wrapping [DropdownMenuItem] only to fix its label
 * to [teddReaderTypography]'s `settingTitle` style — otherwise a plain forward of Material's own
 * [DropdownMenuItem] parameters.
 *
 * @param text The item's label.
 * @param onClick Invoked when the item is tapped; the caller is responsible for dismissing the
 * enclosing [TeddDropdownMenu] itself.
 * @param modifier Modifier applied to the underlying [DropdownMenuItem].
 * @param enabled Whether the item responds to taps.
 * @param leadingIcon Content shown before the label; omitted when null.
 * @param trailingIcon Content shown after the label; omitted when null.
 */
@Composable
fun TeddDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val typography = teddReaderTypography()
    DropdownMenuItem(
        text = { TeddText(text = text, style = typography.settingTitle) },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}

/** Compose preview rendering an expanded [TeddDropdownMenu] with two [TeddDropdownMenuItem]s. */
@Preview
@Composable
private fun TeddDropdownMenuPreview() {
    TeddPreviewSurface {
        Box(modifier = Modifier.padding(teddReaderSpacing().medium)) {
            TeddDropdownMenu(
                expanded = true,
                onDismissRequest = {},
            ) {
                TeddDropdownMenuItem(text = "Search", onClick = {})
                TeddDropdownMenuItem(text = "Document info", onClick = {})
            }
        }
    }
}
