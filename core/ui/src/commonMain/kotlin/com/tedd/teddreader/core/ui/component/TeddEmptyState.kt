package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun TeddEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            text = title,
            style = typography.documentTitle,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = typography.settingDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke()
    }
}

@Preview
@Composable
private fun TeddEmptyStatePreview() {
    TeddPreviewSurface {
        TeddEmptyState(
            title = "No books yet",
            description = "Add TXT, PDF, or EPUB files to start reading.",
            action = {
                TeddButton(text = "Open file", onClick = {})
            },
        )
    }
}
