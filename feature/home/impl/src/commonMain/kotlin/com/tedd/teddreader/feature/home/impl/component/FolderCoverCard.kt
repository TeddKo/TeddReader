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
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.collections.immutable.ImmutableList
import org.jetbrains.compose.resources.stringResource

/**
 * A folder tile in the home library grid: a mosaic of its own documents' covers, its name and
 * document count, and — when the caller allows either action — an overflow menu to rename or
 * delete the folder.
 *
 * @param folder the folder this card represents.
 * @param previewDocuments the leading documents to render as this folder's cover mosaic.
 * @param remainingDocumentCount how many more documents the folder holds beyond [previewDocuments];
 *   shown as a count overlay on the mosaic's last tile when greater than zero.
 * @param documentCoverImages cover image bytes keyed by document id, looked up for each preview tile.
 * @param onClick invoked when the card itself is tapped, to open the folder.
 * @param modifier applied to the outer [Column].
 * @param onRenameClick invoked to start renaming this folder, or null to omit the rename menu entry;
 *   together with [onDeleteClick] being null, this also hides the overflow menu button entirely.
 * @param onDeleteClick invoked to delete this folder, or null to omit the delete menu entry.
 * @param onLoadCover invoked for preview documents that are visible without cached cover bytes yet.
 */
@Composable
fun FolderCoverCard(
    folder: LibraryFolder,
    previewDocuments: ImmutableList<DocumentMetadata>,
    remainingDocumentCount: Int,
    documentCoverImages: Map<String, ByteArray>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRenameClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onLoadCover: ((DocumentMetadata) -> Unit)? = null,
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
                onLoadCover = onLoadCover,
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

/**
 * The grid of cover thumbnails [FolderCoverCard] shows for one folder: up to four documents laid
 * out into a 1x1, 2xN, or 4xN grid depending on how many there are, sized to fit the available
 * constraints while keeping each tile's own cover aspect ratio.
 *
 * @param previewDocuments the documents to render as tiles, in order.
 * @param remainingDocumentCount how many further documents exist beyond [previewDocuments]; drawn
 *   as a translucent count overlay on the last tile when greater than zero.
 * @param documentCoverImages cover image bytes keyed by document id.
 * @param modifier applied to the outer [BoxWithConstraints].
 */
@Composable
private fun FolderCoverMosaic(
    previewDocuments: ImmutableList<DocumentMetadata>,
    remainingDocumentCount: Int,
    documentCoverImages: Map<String, ByteArray>,
    modifier: Modifier = Modifier,
    onLoadCover: ((DocumentMetadata) -> Unit)? = null,
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
                        val coverImageBytes = documentCoverImages[document.id.value]
                        LaunchedEffect(document.id.value, coverImageBytes) {
                            if (coverImageBytes == null && document.supportsRepositoryCover()) onLoadCover?.invoke(document)
                        }
                        Box(
                            modifier = Modifier
                                .width(tileWidth)
                                .height(tileHeight)
                                .clip(teddReaderShapes().small),
                        ) {
                            DocumentCover(
                                coverImageBytes = coverImageBytes,
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

private fun DocumentMetadata.supportsRepositoryCover(): Boolean =
    format == DocumentFormat.PDF || format == DocumentFormat.EPUB || format == DocumentFormat.CBZ
