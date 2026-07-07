package com.tedd.teddreader.feature.home.impl.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddListItem

@Composable
fun DocumentListItem(
    document: DocumentMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = teddReaderTypography()

    TeddListItem(
        title = document.location.displayName,
        modifier = modifier.fillMaxWidth(),
        supportingText = buildDocumentMeta(document),
        onClick = onClick,
        leadingContent = {
            TeddChip(text = document.format.name)
        },
        trailingContent = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                    text = "Open",
                    style = typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                document.pageCount?.let { pageCount ->
                    Text(
                        text = "$pageCount pages",
                        style = typography.readerCaption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

private fun buildDocumentMeta(document: DocumentMetadata): String = buildString {
    append(document.location.sizeBytes.toReadableSize())
    document.pageCount?.let { pageCount ->
        append(" • ")
        append(pageCount)
        append(" pages")
    }
}

private fun Long.toReadableSize(): String = when {
    this >= 1_000_000L -> "${this / 1_000_000L} MB"
    this >= 1_000L -> "${this / 1_000L} KB"
    else -> "$this B"
}

@Preview
@Composable
private fun DocumentListItemPreview() {
    TeddReaderTheme {
        DocumentListItem(
            document = DocumentMetadata(
                id = DocumentId("preview"),
                location = DocumentLocation(
                    sourceUri = "file:///preview.txt",
                    displayName = "Preview Book.txt",
                    sizeBytes = 42_000L,
                ),
                format = DocumentFormat.TXT,
                addedAtEpochMillis = 0L,
                pageCount = 120,
            ),
            onClick = {},
            modifier = Modifier.padding(teddReaderSpacing().screenPadding),
        )
    }
}
