package com.tedd.teddreader.feature.home.impl.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddDropdownMenu
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.extension.teddClickable
import com.tedd.teddreader.core.ui.extension.teddSurface
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.add_to_favorites
import com.tedd.teddreader.core.ui.generated.resources.delete_from_library
import com.tedd.teddreader.core.ui.generated.resources.document_actions
import com.tedd.teddreader.core.ui.generated.resources.document_pages
import com.tedd.teddreader.core.ui.generated.resources.remove_from_favorites
import com.tedd.teddreader.core.ui.generated.resources.select_document
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.jetbrains.compose.resources.stringResource

/** 전체 크기 [DocumentCard]가 요청하는 표지 디코딩 너비(px). */
internal const val DocumentCardCoverWidthPx = 360

/** 전체 크기 [DocumentCard]가 요청하는 표지 디코딩 높이(px). */
internal const val DocumentCardCoverHeightPx = 480

/** 폴더 모자이크에 표시하는 작은 표지가 요청하는 디코딩 너비(px). */
internal const val DocumentMosaicCoverWidthPx = 120

/** 폴더 모자이크에 표시하는 작은 표지가 요청하는 디코딩 높이(px). */
internal const val DocumentMosaicCoverHeightPx = 160

/** [BookCoverFallback]이 그리는 책등 색상 bar의 고정 너비. */
private val BookCoverFallbackSpineWidth = 14.dp

/** [BookCoverFallback]이 책등 bar 뒤에 그리는 얇은 경계선이 시작 가장자리에서 떨어진 거리. */
private val BookCoverFallbackEdgeLineOffset = 18.dp

/** [BookCoverFallback]이 그리는 얇은 경계선의 고정 너비. */
private val BookCoverFallbackEdgeLineWidth = 1.dp

/**
 * 홈 라이브러리 그리드의 단일 문서 타일이다. 표지 그림, 형식 chip, 제목과 메타데이터를 표시하며 카드별
 * 더보기 메뉴와 선택적인 다중 선택 affordance를 제공한다.
 *
 * @param document 이 카드가 나타내는 문서.
 * @param coverImageBytes 사용할 수 있을 때 제공하는 미리 디코딩된 표지 바이트. 카드가 원본 파일을 직접
 *   디코딩하지 않아도 된다. null이면 이미지 문서는 [document]의 원본 URI를 디코딩하고, 그 외에는
 *   [BookCoverFallback]을 사용한다.
 * @param selected 이 카드가 현재 다중 선택에 포함되는지 여부. 카드 전체를 채우는 표지 위에서는 테두리만
 *   보이지 않았으므로 갤러리 picker가 선택 항목을 어둡게 하는 것처럼 표지를 어둡게 한다
 *   ([SelectedCoverDimAlpha] 참고).
 * @param onClick 일반 tap 때 호출한다.
 * @param singleClick 이 카드의 tap이 앱 전체 단일 클릭 navigation guard에 참여할지 여부
 *   (`teddClickable`의 `singleClick` 매개변수 참고). 같은 [onClick] slot이 호출자에 따라 서로 다른 두
 *   작업을 하므로 카드 자체는 이를 결정할 수 없다. 선택이 없으면 문서를 reader에서 여는 destination
 *   push로 중복 tap을 막아야 한다. 선택이 있으면 이 카드의 다중 선택 소속을 전환하는 작업으로, 호출자가
 *   여러 카드를 빠르게 연속 tap할 수 있으며 앱 전체를 공유하는 guard가 의도한 두 번째 tap을 삼키면 안
 *   된다. 호출자는 앞의 경우에만 true를 전달한다.
 * @param onLongClick 길게 누를 때 호출한다. 주변 섹션이 선택을 제공하지 않는 곳에서는 null이며, 아무 작업도
 *   할 수 없는 선택을 시작하는 대신 길게 누르기를 처리하지 않는다.
 * @param actionsExpanded 이 카드의 더보기 메뉴가 현재 열려 있는지 여부.
 * @param onShowActions 더보기 button을 눌러 메뉴를 열 때 호출한다.
 * @param onDismissActions 더보기 메뉴를 닫아야 할 때 호출한다.
 * @param onBookmarkClick 메뉴의 즐겨찾기 추가/제거 항목을 누를 때 호출한다.
 * @param onDeleteClick 메뉴의 삭제 항목을 누를 때 호출한다.
 * @param modifier 카드 root에 적용할 modifier.
 */
@Composable
fun DocumentCard(
    document: DocumentMetadata,
    coverImageBytes: ByteArray? = null,
    selected: Boolean,
    onClick: () -> Unit,
    singleClick: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    actionsExpanded: Boolean,
    onShowActions: () -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLoadCover: (() -> Unit)? = null,
) {
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val shape = teddReaderShapes().medium
    val spacing = teddReaderSpacing()
    val borderColor = if (selected) colors.primary else colors.outlineVariant
    val backgroundColor = if (selected) {
        colors.secondaryContainer
    } else {
        colors.surfaceContainerLow
    }
    val actionsDescription = stringResource(Res.string.document_actions)

    LaunchedEffect(document.id.value, coverImageBytes) {
        if (coverImageBytes == null && document.supportsRepositoryCover()) onLoadCover?.invoke()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .teddSurface(shape = shape, borderColor = borderColor, backgroundColor = backgroundColor)
            .semantics {
                this.selected = selected
            }
            .teddClickable(
                onClick = onClick,
                shape = shape,
                role = Role.Button,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClick?.let { stringResource(Res.string.select_document) },
                singleClick = singleClick,
            ),
    ) {
        DocumentCover(
            coverImageBytes = coverImageBytes,
            sourceUri = document.location.sourceUri.takeIf { document.format == DocumentFormat.IMAGE },
            selected = selected,
            widthPx = DocumentCardCoverWidthPx,
            heightPx = DocumentCardCoverHeightPx,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to colors.scrim.copy(alpha = 0.08f),
                        0.48f to colors.scrim.copy(alpha = 0.05f),
                        1f to colors.scrim.copy(alpha = 0.78f),
                    ),
                ),
        )

        if (selected) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(colors.scrim.copy(alpha = SelectedCoverDimAlpha)),
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(spacing.small),
        ) {
            TeddIconButton(
                onClick = onShowActions,
                contentDescription = actionsDescription,
            ) {
                TeddIcon(
                    imageVector = TeddIcons.MoreVert,
                    contentDescription = null,
                    tint = colors.onSurface,
                )
            }

            TeddDropdownMenu(
                expanded = actionsExpanded,
                onDismissRequest = onDismissActions,
            ) {
                TeddDropdownMenuItem(
                    text = if (document.isBookmarked) {
                        stringResource(Res.string.remove_from_favorites)
                    } else {
                        stringResource(Res.string.add_to_favorites)
                    },
                    onClick = onBookmarkClick,
                    leadingIcon = {
                        TeddIcon(
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
                    text = stringResource(Res.string.delete_from_library),
                    onClick = onDeleteClick,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.xSmall),
        ) {
            TeddChip(text = document.format.name)
            TeddText(
                text = document.location.displayName,
                style = typography.settingTitle,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TeddText(
                text = buildDocumentMeta(document),
                style = typography.documentMeta,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 문서 표지 그림이다. [BookCoverFallback] 위에 디코딩한 이미지를 표시하거나 아직 이미지 데이터가 없으면
 * fallback만 표시한다.
 *
 * @param coverImageBytes 미리 디코딩된 표지 바이트. [sourceUri]와 둘 다 null이 아니면 우선한다.
 * @param sourceUri Coil이 직접 디코딩할 수 있는 URI. [coverImageBytes]가 null인 이미지 형식 문서에만 사용한다.
 * @param selected 표지가 아직 로드되지 않았을 때 fallback 색상이 카드의 선택 상태와 일치하도록
 *   [BookCoverFallback]에 전달한다.
 * @param widthPx Coil에 요청할 디코딩 이미지 너비(px).
 * @param heightPx Coil에 요청할 디코딩 이미지 높이(px).
 * @param modifier 표지 root에 적용할 modifier.
 */
@Composable
internal fun DocumentCover(
    coverImageBytes: ByteArray?,
    sourceUri: String? = null,
    selected: Boolean,
    widthPx: Int,
    heightPx: Int,
    modifier: Modifier = Modifier,
) {
    val platformContext = LocalPlatformContext.current
    val coverRequest = remember(coverImageBytes, sourceUri, platformContext, widthPx, heightPx) {
        (coverImageBytes ?: sourceUri)?.let { data ->
            ImageRequest.Builder(platformContext)
                .data(data)
                .size(widthPx, heightPx)
                .build()
        }
    }

    Box(modifier = modifier) {
        BookCoverFallback(
            selected = selected,
            modifier = Modifier.fillMaxSize(),
        )
        if (coverRequest != null) {
            AsyncImage(
                model = coverRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * 문서의 실제 표지 아래나 그 대신 표시하는 책등 모양 placeholder다. 그림을 로드하는 중이거나 사용할 수
 * 없어도 카드가 빈 상자로 렌더링되지 않게 한다.
 *
 * @param selected 이 fallback을 소유한 카드의 선택 여부. fallback이 시각적으로 어긋나지 않도록 책등과
 *   surface를 카드 자체의 선택 색상에 맞춘다.
 * @param modifier fallback root에 적용할 modifier.
 */
@Composable
internal fun BookCoverFallback(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = teddReaderColors()
    val spineColor = if (selected) {
        colors.primary.copy(alpha = 0.72f)
    } else {
        colors.primary.copy(alpha = 0.58f)
    }
    val lineColor = colors.onSurface.copy(alpha = 0.12f)
    val surfaceColor = if (selected) {
        colors.secondaryContainer
    } else {
        colors.surfaceContainerHigh
    }

    Box(
        modifier = modifier.background(surfaceColor),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(BookCoverFallbackSpineWidth)
                .background(spineColor),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(start = BookCoverFallbackEdgeLineOffset)
                .width(BookCoverFallbackEdgeLineWidth)
                .background(lineColor),
        )
    }
}

/**
 * 파일 크기와, 알 수 있을 때는 페이지 수를 bullet로 구분한 카드 메타데이터 줄을 만든다.
 *
 * @param document 위치 크기와 페이지 수를 형식화할 문서.
 * @return 실제 존재하는 부분 사이에만 bullet을 넣은 메타데이터 줄. 예: "42 KB • 120 pages".
 */
@Composable
private fun buildDocumentMeta(document: DocumentMetadata): String = buildList {
    add(document.location.sizeBytes.toReadableSize())
    document.pageCount?.let { add(stringResource(Res.string.document_pages, it)) }
}.joinToString(" • ")

/**
 * 바이트 수를 짧고 읽기 쉬운 크기로 형식화한다. 카드의 메타데이터 줄에는 정밀한 값을 표시할 공간이 없으므로
 * 소수점을 표시하지 않고 정수 MB/KB/B로 내림한다.
 *
 * @receiver 바이트 단위 크기.
 * @return 숫자를 정수로 유지하는 가장 큰 단위("MB", "KB", "B")로 형식화한 크기.
 */
private fun Long.toReadableSize(): String = when {
    this >= 1_000_000L -> "${this / 1_000_000L} MB"
    this >= 1_000L -> "${this / 1_000L} KB"
    else -> "$this B"
}

/** 긴 한국어 파일 이름을 사용하고 선택하지 않은 [DocumentCard]의 Compose preview. */
@Preview(widthDp = 270, heightDp = 360)
@Composable
private fun DocumentCardPreview() {
    TeddReaderTheme {
        DocumentCard(
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
            selected = false,
            onClick = {},
            onLongClick = {},
            actionsExpanded = false,
            onShowActions = {},
            onDismissActions = {},
            onBookmarkClick = {},
            onDeleteClick = {},
            modifier = Modifier.aspectRatio(3f / 4f),
        )
    }
}

private fun DocumentMetadata.supportsRepositoryCover(): Boolean =
    format == DocumentFormat.PDF || format == DocumentFormat.EPUB || format == DocumentFormat.CBZ

/** 선택한 카드를 어둡게 하는 정도다. 선택 상태로 인식할 만큼 어둡고 내용을 볼 수 있을 만큼 밝다. */
private const val SelectedCoverDimAlpha = 0.42f
