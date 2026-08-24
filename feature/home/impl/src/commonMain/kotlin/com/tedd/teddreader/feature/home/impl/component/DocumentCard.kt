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

/** Requested cover decode width, in pixels, for a full-size [DocumentCard]. */
internal const val DocumentCardCoverWidthPx = 360

/** Requested cover decode height, in pixels, for a full-size [DocumentCard]. */
internal const val DocumentCardCoverHeightPx = 480

/** Requested cover decode width, in pixels, for the smaller covers shown in a folder mosaic. */
internal const val DocumentMosaicCoverWidthPx = 120

/** Requested cover decode height, in pixels, for the smaller covers shown in a folder mosaic. */
internal const val DocumentMosaicCoverHeightPx = 160

/**
 * A single document tile in the home library grid: cover art, format chip, title, and metadata,
 * with a per-card overflow menu and an optional multi-select affordance.
 *
 * @param document The document this card represents.
 * @param coverImageBytes Pre-decoded cover bytes, when available, so the card does not have to
 *   decode the source file itself; null falls back to decoding [document]'s source URI for image
 *   documents, or to [BookCoverFallback] otherwise.
 * @param selected Whether this card is part of the current multi-select. Selection dims the cover
 *   (see [SelectedCoverDimAlpha]) because the border alone never showed against a cover that fills
 *   the whole card, the way a picker in a gallery dims its chosen items.
 * @param onClick Called on a plain tap.
 * @param singleClick Whether this card's tap joins the app-wide single-click navigation guard (see
 *   `teddClickable`'s own `singleClick` parameter). The card cannot decide this for itself because
 *   the very same [onClick] slot serves two different jobs depending on the caller: opening the
 *   document in the reader (a destination push, where a duplicate tap must be swallowed) while no
 *   selection is active, or toggling this card's membership in a multi-select (where a caller may
 *   legitimately tap several different cards in quick succession, and the guard's app-wide sharing
 *   would otherwise eat the second of two intentional taps) once one is. The caller passes true only
 *   for the former.
 * @param onLongClick Called on a long press; null where the surrounding section does not offer
 *   selection, which leaves the long press unhandled rather than starting a selection nothing can
 *   act on.
 * @param actionsExpanded Whether this card's overflow menu is currently open.
 * @param onShowActions Called when the overflow button is tapped to open the menu.
 * @param onDismissActions Called when the overflow menu should close.
 * @param onBookmarkClick Called when the menu's favourite/unfavourite item is tapped.
 * @param onDeleteClick Called when the menu's delete item is tapped.
 * @param modifier The modifier applied to the card's root.
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
 * The cover artwork for a document: a decoded image over [BookCoverFallback], or just the
 * fallback when no image data is available yet.
 *
 * @param coverImageBytes Pre-decoded cover bytes; takes priority over [sourceUri] when both are
 *   non-null.
 * @param sourceUri A URI Coil can decode directly, used only when [coverImageBytes] is null (an
 *   image-format document without a cached cover).
 * @param selected Forwarded to [BookCoverFallback] so its color matches the card's own selection
 *   state even while no cover has loaded.
 * @param widthPx The width, in pixels, requested from Coil for the decoded image.
 * @param heightPx The height, in pixels, requested from Coil for the decoded image.
 * @param modifier The modifier applied to the cover's root.
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
 * A drawn book-spine placeholder shown under (or instead of) a document's real cover, so a card
 * never renders as an empty box while artwork is still loading or unavailable.
 *
 * @param selected Whether the owning card is selected; tints the spine and surface to match the
 *   card's own selection colors instead of leaving the fallback visually out of step.
 * @param modifier The modifier applied to the fallback's root.
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
                .width(14.dp)
                .background(spineColor),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(start = 18.dp)
                .width(1.dp)
                .background(lineColor),
        )
    }
}

/**
 * Builds the card's bullet-separated metadata line: file size, and page count when known.
 *
 * @param document The document whose location size and page count are formatted.
 * @return The metadata line, e.g. "42 KB • 120 pages", with a bullet only between parts that are
 *   actually present.
 */
@Composable
private fun buildDocumentMeta(document: DocumentMetadata): String = buildList {
    add(document.location.sizeBytes.toReadableSize())
    document.pageCount?.let { add(stringResource(Res.string.document_pages, it)) }
}.joinToString(" • ")

/**
 * Formats a byte count as a short human-readable size, rounding down to whole MB/KB/B rather than
 * showing decimals, since a card's metadata line has no room for precision.
 *
 * @receiver A size in bytes.
 * @return The size formatted with the largest unit ("MB", "KB", or "B") that keeps the number
 *   whole.
 */
private fun Long.toReadableSize(): String = when {
    this >= 1_000_000L -> "${this / 1_000_000L} MB"
    this >= 1_000L -> "${this / 1_000L} KB"
    else -> "$this B"
}

/** Compose preview of [DocumentCard] with a long Korean file name, unselected. */
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

/** How far a selected card is dimmed: dark enough to read as chosen, light enough to still see it. */
private fun DocumentMetadata.supportsRepositoryCover(): Boolean =
    format == DocumentFormat.PDF || format == DocumentFormat.EPUB || format == DocumentFormat.CBZ

private const val SelectedCoverDimAlpha = 0.42f
