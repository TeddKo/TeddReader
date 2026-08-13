package com.tedd.teddreader.feature.home.impl.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.delete
import com.tedd.teddreader.core.ui.generated.resources.folder_actions
import com.tedd.teddreader.core.ui.generated.resources.folder_cover_more_documents
import com.tedd.teddreader.core.ui.generated.resources.folder_row_description
import com.tedd.teddreader.core.ui.generated.resources.rename_folder
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.feature.home.impl.LibraryFolder
import org.jetbrains.compose.resources.stringResource

@Composable
fun FolderCoverCard(
    folder: LibraryFolder,
    previewDocuments: List<DocumentMetadata>,
    remainingDocumentCount: Int,
    documentCoverImages: Map<String, ByteArray>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRenameClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
) {
    val shape = teddReaderShapes().medium
    val spacing = DefaultTeddReaderSpacing
    val typography = teddReaderTypography()
    val actionsDescription = stringResource(Res.string.folder_actions, folder.name)
    val canShowOverflow = onRenameClick != null || onDeleteClick != null
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .clickable(onClick = onClick)
            .padding(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            FolderCoverMosaic(
                previewDocuments = previewDocuments,
                remainingDocumentCount = remainingDocumentCount,
                documentCoverImages = documentCoverImages,
                modifier = Modifier.fillMaxSize(),
            )
            if (canShowOverflow) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    TeddIconButton(
                        onClick = { menuExpanded = true },
                        contentDescription = actionsDescription,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            imageVector = TeddIcons.MoreVert,
                            contentDescription = null,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        onRenameClick?.let {
                            TeddDropdownMenuItem(
                                text = stringResource(Res.string.rename_folder),
                                onClick = {
                                    menuExpanded = false
                                    it()
                                },
                            )
                        }
                        onDeleteClick?.let {
                            TeddDropdownMenuItem(
                                text = stringResource(Res.string.delete),
                                onClick = {
                                    menuExpanded = false
                                    it()
                                },
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
        ) {
            Text(
                text = folder.name,
                style = typography.settingTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.folder_row_description, folder.documentCount),
                style = typography.documentMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FolderCoverMosaic(
    previewDocuments: List<DocumentMetadata>,
    remainingDocumentCount: Int,
    documentCoverImages: Map<String, ByteArray>,
    modifier: Modifier = Modifier,
) {
    val spacing = DefaultTeddReaderSpacing.xSmall
    val previewSize = previewDocuments.size
    val columns = when {
        previewSize == 0 -> 1
        previewSize <= 4 -> minOf(2, previewSize.coerceAtLeast(1))
        else -> 4
    }
    val rows = ((previewSize + columns - 1) / columns).coerceAtLeast(1)

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        val horizontalGaps = spacing * (columns - 1)
        val verticalGaps = spacing * (rows - 1)
        val widthFit = ((maxWidth - horizontalGaps) / columns).coerceAtLeast(0.dp)
        val heightFit = (((maxHeight - verticalGaps) / rows) * 3f / 4f).coerceAtLeast(0.dp)
        val tileWidth = minOf(widthFit, heightFit)
        val tileHeight = tileWidth * 4f / 3f
        val mosaicWidth = if (previewSize == 0) 0.dp else tileWidth * columns + spacing * (columns - 1)
        val mosaicHeight = if (previewSize == 0) 0.dp else tileHeight * rows + spacing * (rows - 1)

        Column(
            modifier = Modifier
                .width(mosaicWidth)
                .height(mosaicHeight),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            previewDocuments.chunked(columns).forEachIndexed { chunkIndex, rowDocuments ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                ) {
                    rowDocuments.forEachIndexed { columnIndex, document ->
                        val itemIndex = chunkIndex * columns + columnIndex
                        Box(
                            modifier = Modifier
                                .width(tileWidth)
                                .height(tileHeight)
                                .clip(teddReaderShapes().small),
                        ) {
                            DocumentCover(
                                coverImageBytes = documentCoverImages[document.id.value],
                                sourceUri = document.location.sourceUri.takeIf {
                                    document.format == DocumentFormat.IMAGE
                                },
                                selected = false,
                                widthPx = DocumentMosaicCoverWidthPx,
                                heightPx = DocumentMosaicCoverHeightPx,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (itemIndex == previewDocuments.lastIndex && remainingDocumentCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.56f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(Res.string.folder_cover_more_documents, remainingDocumentCount),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = teddReaderTypography().titleMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
