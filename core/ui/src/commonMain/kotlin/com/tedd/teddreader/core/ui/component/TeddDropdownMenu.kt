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
