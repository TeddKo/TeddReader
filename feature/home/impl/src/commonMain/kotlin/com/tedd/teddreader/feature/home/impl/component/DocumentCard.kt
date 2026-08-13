package com.tedd.teddreader.feature.home.impl.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.add_to_favorites
import com.tedd.teddreader.core.ui.generated.resources.delete_from_library
import com.tedd.teddreader.core.ui.generated.resources.document_actions
import com.tedd.teddreader.core.ui.generated.resources.document_pages
import com.tedd.teddreader.core.ui.generated.resources.remove_from_favorites
import com.tedd.teddreader.core.ui.generated.resources.select_document
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun DocumentCard(
    document: DocumentMetadata,
    coverImageBytes: ByteArray? = null,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    actionsExpanded: Boolean,
    onShowActions: () -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typography = teddReaderTypography()
    val shape = teddReaderShapes().medium
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val interactionSource = remember { MutableInteractionSource() }
    val actionsDescription = stringResource(Res.string.document_actions)
    val platformContext = LocalPlatformContext.current
    val coverRequest = remember(coverImageBytes, platformContext) {
        coverImageBytes?.let { bytes ->
            ImageRequest.Builder(platformContext)
                .data(bytes)
                .size(360, 480)
                .build()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(backgroundColor)
            .border(BorderStroke(1.dp, borderColor), shape)
            .semantics {
                this.selected = selected
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = stringResource(Res.string.select_document),
                onLongClick = onLongClick,
            ),
    ) {
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

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.08f),
                        0.48f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.05f),
                        1f to MaterialTheme.colorScheme.scrim.copy(alpha = 0.78f),
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(DefaultTeddReaderSpacing.small),
        ) {
            IconButton(
                onClick = onShowActions,
                modifier = Modifier.semantics {
                    contentDescription = actionsDescription
                },
                shape = CircleShape,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) {
                Icon(
                    imageVector = TeddIcons.MoreVert,
                    contentDescription = null,
                )
            }

            DropdownMenu(
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
                    text = stringResource(Res.string.delete_from_library),
                    onClick = onDeleteClick,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(DefaultTeddReaderSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(DefaultTeddReaderSpacing.xSmall),
        ) {
            TeddChip(text = document.format.name)
            Text(
                text = document.location.displayName,
                style = typography.settingTitle,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildDocumentMeta(document),
                style = typography.documentMeta,
                color = Color.White.copy(alpha = 0.82f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

    }
}

@Composable
private fun BookCoverFallback(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val spineColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.58f)
    }
    val lineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val surfaceColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
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

@Composable
private fun buildDocumentMeta(document: DocumentMetadata): String = buildList {
    add(document.location.sizeBytes.toReadableSize())
    document.pageCount?.let { add(stringResource(Res.string.document_pages, it)) }
}.joinToString(" • ")

private fun Long.toReadableSize(): String = when {
    this >= 1_000_000L -> "${this / 1_000_000L} MB"
    this >= 1_000L -> "${this / 1_000L} KB"
    else -> "$this B"
}

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
            modifier = Modifier,
        )
    }
}
