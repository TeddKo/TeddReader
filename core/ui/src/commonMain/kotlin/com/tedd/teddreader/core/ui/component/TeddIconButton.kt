package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.ui.icon.TeddIcons

@Composable
fun TeddIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val semanticsModifier = if (!contentDescription.isNullOrBlank()) {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    } else {
        Modifier
    }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(semanticsModifier),
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
            Icon(imageVector = TeddIcons.BookmarkFilled, contentDescription = null)
        }
    }
}
