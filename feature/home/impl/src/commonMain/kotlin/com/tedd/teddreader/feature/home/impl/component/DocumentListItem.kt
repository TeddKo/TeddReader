package com.tedd.teddreader.feature.home.impl.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
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
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.icon.TeddIcons

@Composable
fun DocumentListItem(
    document: DocumentMetadata,
    onClick: () -> Unit,
    actionsExpanded: Boolean,
    onShowActions: () -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
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
        onLongClick = onShowActions,
        showDivider = showDivider,
        contentPadding = contentPadding,
        leadingContent = {
            TeddChip(text = document.format.name)
        },
        trailingContent = {
            Box {
                TeddIconButton(
                    onClick = onShowActions,
                    contentDescription = "Document actions",
                ) {
                    Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded = actionsExpanded,
                    onDismissRequest = onDismissActions,
                ) {
                    TeddDropdownMenuItem(
                        text = if (document.isBookmarked) "Remove from favorites" else "Add to favorites",
                        onClick = onBookmarkClick,
                        leadingIcon = {
                            Icon(
                                imageVector = if (document.isBookmarked) {
                                    TeddIcons.BookmarkFilled
                                } else {
                                    TeddIcons.BookmarkOutline
                                },
                                contentDescription = null,
                            )
                        },
                    )
                    TeddDropdownMenuItem(
                        text = "Delete from library",
                        onClick = onDeleteClick,
                    )
                }
            }
        },
    )
}

private fun buildDocumentMeta(document: DocumentMetadata): String = buildList {
    add(document.location.sizeBytes.toReadableSize())
    document.pageCount?.let { add("$it pages") }
}.joinToString(" • ")

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
            actionsExpanded = false,
            onShowActions = {},
            onDismissActions = {},
            onBookmarkClick = {},
            onDeleteClick = {},
            modifier = Modifier.padding(teddReaderSpacing().screenPadding),
        )
    }
}
