package com.tedd.teddreader.feature.home.impl.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddListItem

@Composable
fun DocumentListItem(
    document: DocumentMetadata,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.screenPadding,
        vertical = DefaultTeddReaderSpacing.small,
    ),
) {
    TeddListItem(
        title = document.location.displayName,
        modifier = modifier.fillMaxWidth(),
        supportingText = buildDocumentMeta(document),
        onClick = onClick,
        contentPadding = contentPadding,
        leadingContent = {
            TeddChip(text = document.format.name)
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

@Preview(widthDp = 280)
@Composable
private fun DocumentListItemPreview() {
    TeddReaderTheme {
        DocumentListItem(
            document = DocumentMetadata(
                id = DocumentId("preview"),
                location = DocumentLocation(
                    sourceUri = "file:///preview.txt",
                    displayName = "길고 차분한 한국어 파일 이름도 두 줄 안에서 안정적으로 보여야 합니다.txt",
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
