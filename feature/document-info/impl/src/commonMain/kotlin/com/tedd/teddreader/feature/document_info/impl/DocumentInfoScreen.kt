package com.tedd.teddreader.feature.document_info.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

private val ScreenMaxWidth = 720.dp
private val ReadingStatsTwoColumnMinWidth = 320.dp

@Composable
fun DocumentInfoRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentInfoViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    DocumentInfoScreen(
        uiState = uiState,
        onBack = onBack,
        listState = listState,
        modifier = modifier,
    )
}

@Composable
fun DocumentInfoScreen(
    uiState: DocumentInfoUiState,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val metadata = uiState.metadata

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.document_info),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        Icon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = ScreenMaxWidth)
                    .fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                uiState.errorMessage?.let { message ->
                    item { TeddErrorBanner(message = message) }
                }

                if (uiState.isLoading) {
                    item {
                        TeddLoadingIndicator(message = stringResource(Res.string.loading_document_info))
                    }
                } else {
                    metadata?.let {
                        item {
                            Text(text = it.location.displayName, style = typography.titleMedium)
                        }
                        item {
                            TeddOptionGroup(
                                title = stringResource(Res.string.overview),
                                description = stringResource(Res.string.overview_description),
                                headerPadding = PaddingValues(),
                            ) {
                                SelectionContainer {
                                    MetadataRow(label = stringResource(Res.string.location), value = it.location.sourceUri)
                                }
                                MetadataRow(label = stringResource(Res.string.format), value = it.format.displayName(unknown = stringResource(Res.string.unknown_format)))
                                MetadataRow(label = stringResource(Res.string.size), value = formatSize(it.location.sizeBytes, unavailable = stringResource(Res.string.not_available)))
                                MetadataRow(label = stringResource(Res.string.pages), value = formatPageCount(it.pageCount, unavailable = stringResource(Res.string.not_available)))
                                MetadataRow(label = stringResource(Res.string.current_page), value = formatPagePosition(uiState.pageIndex, unavailable = stringResource(Res.string.not_available), separator = stringResource(Res.string.page_position_separator)))
                            }
                        }
                    }

                    item {
                        TeddOptionGroup(
                            title = stringResource(Res.string.reading_stats),
                            description = stringResource(Res.string.reading_stats_description),
                            headerPadding = PaddingValues(),
                        ) {
                            ReadingStatsContent(
                                readingTime = formatDuration(uiState.stats?.activeMillis, unavailable = stringResource(Res.string.not_available)),
                                readingPace = formatReadingPace(uiState.stats, unavailable = stringResource(Res.string.not_available), suffix = stringResource(Res.string.reading_pace_suffix)),
                                characters = formatCount(metadata?.characterCount, unavailable = stringResource(Res.string.not_available)),
                                words = formatCount(metadata?.wordCount, unavailable = stringResource(Res.string.not_available)),
                            )
                        }
                    }

                    item {
                        TeddOptionGroup(
                            title = stringResource(Res.string.recent_sessions),
                            description = stringResource(Res.string.recent_sessions_description),
                            headerPadding = PaddingValues(),
                        ) {
                            if (uiState.sessions.isEmpty()) {
                                Text(
                                    text = stringResource(Res.string.no_reading_sessions),
                                    style = typography.settingDescription,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                uiState.sessions.take(10).forEachIndexed { index, session ->
                                    TeddListItem(
                                        title = stringResource(Res.string.session_title, index + 1),
                                        supportingText = formatDuration(session.activeMillis),
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

@Composable
private fun ReadingStatsContent(
    readingTime: String,
    readingPace: String,
    characters: String,
    words: String,
) {
    val spacing = teddReaderSpacing()
    val stats = listOf(
        stringResource(Res.string.reading_time) to readingTime,
        stringResource(Res.string.reading_pace) to readingPace,
        stringResource(Res.string.characters) to characters,
        stringResource(Res.string.words) to words,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= ReadingStatsTwoColumnMinWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                stats.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                        verticalAlignment = Alignment.Top,
                    ) {
                        rowItems.forEach { (label, value) ->
                            MetadataRow(label = label, value = value, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                stats.forEach { (label, value) ->
                    MetadataRow(label = label, value = value)
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        Text(text = label, style = typography.settingDescription, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = typography.settingTitle)
    }
}

internal fun formatSize(sizeBytes: Long?, unavailable: String = "Not available"): String {
    if (sizeBytes == null) return unavailable
    if (sizeBytes < 1_024L) return "$sizeBytes B"
    val kilobytes = sizeBytes / 1_024f
    if (kilobytes < 1_024f) return "${formatDecimal(kilobytes)} KB"
    return "${formatDecimal(kilobytes / 1_024f)} MB"
}

internal fun formatDuration(activeMillis: Long?, unavailable: String = "Not available"): String {
    if (activeMillis == null) return unavailable
    val totalSeconds = (activeMillis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0L -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}

internal fun formatReadingPace(
    stats: ReadingStats?,
    unavailable: String = "Not available",
    suffix: String = " words/min",
): String {
    if (stats == null || stats.activeMillis <= 0L || stats.wordsRead <= 0L) return unavailable
    return "${stats.wordsPerMinute.roundToInt()}$suffix"
}

internal fun formatCount(value: Long?, unavailable: String = "Not available"): String = value?.toString() ?: unavailable
internal fun formatPageCount(pageCount: Int?, unavailable: String = "Not available"): String = pageCount?.toString() ?: unavailable

internal fun formatPagePosition(
    pageIndex: PageIndex?,
    unavailable: String = "Not available",
    separator: String = " of ",
): String {
    if (pageIndex == null || pageIndex.total <= 0) return unavailable
    return "${pageIndex.current + 1}$separator${pageIndex.total}"
}

private fun formatDecimal(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

private fun DocumentFormat.displayName(unknown: String = "Unknown format"): String = when (this) {
    DocumentFormat.TXT -> "TXT"
    DocumentFormat.PDF -> "PDF"
    DocumentFormat.EPUB -> "EPUB"
    DocumentFormat.CBZ -> "CBZ"
    DocumentFormat.IMAGE -> "Image"
    DocumentFormat.UNKNOWN -> unknown
}

@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 840)
@Composable
private fun DocumentInfoScreenPreview() {
    TeddReaderTheme {
        DocumentInfoScreen(
            uiState = DocumentInfoUiState(
                isLoading = false,
                metadata = DocumentMetadata(
                    id = DocumentId("preview"),
                    location = DocumentLocation("file:///preview/very/long/path/to/preview.txt", "preview.txt", sizeBytes = 42_500L),
                    format = DocumentFormat.TXT,
                    addedAtEpochMillis = 0L,
                    pageCount = 10,
                    characterCount = 1_000L,
                    wordCount = 200L,
                ),
                pageIndex = PageIndex(current = 3, total = 10),
                stats = ReadingStats(documentId = DocumentId("preview"), activeMillis = 12_345L, charactersRead = 1_000L, wordsRead = 200L),
                sessions = persistentListOf(
                    com.tedd.teddreader.core.domain.repository.ReadingSession(
                        id = "session-1",
                        documentId = DocumentId("preview"),
                        startedAtEpochMillis = 0L,
                        activeMillis = 1_500L,
                        startLocation = ReaderLocation.TextOffset(0L),
                    ),
                ),
            ),
            onBack = {},
            listState = rememberLazyListState(),
        )
    }
}
