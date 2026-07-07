package com.tedd.teddreader.core.ui.component

import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

@Composable
fun TeddIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    } else {
        Modifier
    }
    IconButton(
        onClick = onClick,
        modifier = modifier.then(semanticsModifier),
        enabled = enabled,
        content = content,
    )
}

@Preview
@Composable
private fun TeddIconButtonPreview() {
    TeddPreviewSurface {
        TeddIconButton(
            onClick = {},
            contentDescription = "Favorite",
        ) {
            Text("★")
        }
    }
}
