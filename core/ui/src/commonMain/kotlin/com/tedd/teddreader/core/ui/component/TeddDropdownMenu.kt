package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * A single row inside a Material [DropdownMenu], wrapping [DropdownMenuItem] only to fix its label
 * to [teddReaderTypography]'s `settingTitle` style — otherwise a plain forward of Material's own
 * [DropdownMenuItem] parameters.
 *
 * @param text The item's label.
 * @param onClick Invoked when the item is tapped; the caller is responsible for dismissing the
 * enclosing [DropdownMenu] itself.
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
        text = { Text(text = text, style = typography.settingTitle) },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}

/** Compose preview rendering an expanded [DropdownMenu] with two [TeddDropdownMenuItem]s. */
@Preview
@Composable
private fun TeddDropdownMenuPreview() {
    TeddPreviewSurface {
        Box(modifier = Modifier.padding(teddReaderSpacing().medium)) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = {},
            ) {
                TeddDropdownMenuItem(text = "Search", onClick = {})
                TeddDropdownMenuItem(text = "Document info", onClick = {})
            }
        }
    }
}
