package com.tedd.teddreader.feature.home.impl.component

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddDropdownMenu
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.extension.teddClickable
import com.tedd.teddreader.core.ui.extension.teddSurface
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.delete
import com.tedd.teddreader.core.ui.generated.resources.folder_actions
import com.tedd.teddreader.core.ui.generated.resources.folder_cover_more_documents
import com.tedd.teddreader.core.ui.generated.resources.folder_row_description
import com.tedd.teddreader.core.ui.generated.resources.rename_folder
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.feature.home.impl.LibraryFolder
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import org.jetbrains.compose.resources.stringResource

/**
 * 홈 라이브러리 그리드의 폴더 타일이다. 폴더 문서의 표지 모자이크, 폴더 이름과 문서 수를 표시하고 호출자가
 * 두 작업 중 하나라도 허용하면 폴더 이름을 바꾸거나 삭제하는 더보기 메뉴도 제공한다.
 *
 * @param folder 이 카드가 나타내는 폴더.
 * @param previewDocuments 폴더의 표지 모자이크에 표시할 앞쪽 문서.
 * @param remainingDocumentCount [previewDocuments] 외에 폴더에 더 있는 문서 수. 0보다 크면 모자이크의
 *   마지막 타일에 개수 overlay로 표시한다.
 * @param documentCoverImages 문서 id를 키로 하는 표지 이미지 바이트. 각 미리보기 타일에서 조회한다.
 * @param onClick 카드 자체를 눌러 폴더를 열 때 호출한다.
 * @param singleClick 이 tap이 앱 전체 단일 클릭 navigation guard에 참여할지 여부
 *   (`teddClickable`의 `singleClick` 매개변수 참고). 폴더 열기는 문서 카드의 tap과 달리 전환 동작이 없는
 *   destination push이므로 이 카드의 모든 호출자는 안전하게 true를 전달할 수 있다.
 * @param modifier 바깥쪽 [Column]에 적용할 modifier.
 * @param onRenameClick 폴더 이름 변경을 시작할 때 호출한다. null이면 이름 변경 메뉴 항목을 생략한다.
 *   [onDeleteClick]도 null이면 더보기 메뉴 button 자체를 숨긴다.
 * @param onDeleteClick 폴더를 삭제할 때 호출한다. null이면 삭제 메뉴 항목을 생략한다.
 * @param onLoadCover 캐시된 표지 바이트 없이 표시 중인 미리보기 문서에 대해 호출한다.
 */
@Composable
fun FolderCoverCard(
    folder: LibraryFolder,
    previewDocuments: ImmutableList<DocumentMetadata>,
    remainingDocumentCount: Int,
    documentCoverImages: ImmutableMap<String, ByteArray>,
    onClick: () -> Unit,
    singleClick: Boolean = false,
    modifier: Modifier = Modifier,
    onRenameClick: (() -> Unit)? = null,
    onDeleteClick: (() -> Unit)? = null,
    onLoadCover: ((DocumentMetadata) -> Unit)? = null,
) {
    val shape = teddReaderShapes().medium
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val actionsDescription = stringResource(Res.string.folder_actions, folder.name)
    val canShowOverflow = onRenameClick != null || onDeleteClick != null
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .teddSurface(
                shape = shape,
                borderColor = colors.outlineVariant,
                backgroundColor = colors.surfaceContainerLow,
            )
            .teddClickable(onClick = onClick, shape = shape, role = Role.Button, singleClick = singleClick)
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
                        modifier = Modifier.size(spacing.touchTarget),
                    ) {
                        TeddIcon(
                            imageVector = TeddIcons.MoreVert,
                            contentDescription = null,
                        )
                    }
                    TeddDropdownMenu(
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
            TeddText(
                text = folder.name,
                style = typography.settingTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TeddText(
                text = stringResource(Res.string.folder_row_description, folder.documentCount),
                style = typography.documentMeta,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * [FolderCoverCard]가 폴더 하나에 표시하는 표지 썸네일 그리드다. 문서 수에 따라 최대 4개를 1x1, 2xN 또는
 * 4xN 그리드로 배치하며, 각 타일 자체의 표지 종횡비를 유지하면서 가용 제약에 맞는 크기로 만든다.
 *
 * @param previewDocuments 타일로 표시할 문서. 표시 순서대로 전달한다.
 * @param remainingDocumentCount [previewDocuments] 외에 더 있는 문서 수. 0보다 크면 마지막 타일 위에 반투명
 *   개수 overlay로 그린다.
 * @param documentCoverImages 문서 id를 키로 하는 표지 이미지 바이트.
 * @param modifier 바깥쪽 [BoxWithConstraints]에 적용할 modifier.
 */
@Composable
private fun FolderCoverMosaic(
    previewDocuments: ImmutableList<DocumentMetadata>,
    remainingDocumentCount: Int,
    documentCoverImages: ImmutableMap<String, ByteArray>,
    modifier: Modifier = Modifier,
    onLoadCover: ((DocumentMetadata) -> Unit)? = null,
) {
    val spacing = teddReaderSpacing().xSmall
    val colors = teddReaderColors()
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
                                        .background(colors.scrim.copy(alpha = 0.56f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    TeddText(
                                        text = stringResource(Res.string.folder_cover_more_documents, remainingDocumentCount),
                                        color = colors.onPrimary,
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
