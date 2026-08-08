package com.tedd.teddreader.feature.document_info.impl

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
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
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSurface
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

@Composable
fun DocumentInfoRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentInfoViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    DocumentInfoScreen(
        uiState = uiState,
        onBack = onBack,
        scrollState = scrollState,
        modifier = modifier,
    )
}

@Composable
fun DocumentInfoScreen(
    uiState: DocumentInfoUiState,
    onBack: () -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    if (uiState.isLoading) {
        TeddSurface(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                TeddLoadingIndicator(message = "Loading document info")
            }
        }
        return
    }

    val metadata = uiState.metadata

    TeddSurface(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        TeddScaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TeddTopBar(
                    title = "Document info",
                    navigationIcon = {
                        TeddIconButton(
                            onClick = onBack,
                            contentDescription = "Back",
                        ) {
                            Icon(
                                imageVector = TeddIcons.Back,
                                contentDescription = null,
                            )
                        }
                    },
                )
            },
        ) { scaffoldPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(scaffoldPadding)
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(spacing.medium),
            ) {
                Text(
                    text = metadata?.location?.displayName ?: uiState.documentId,
                    style = typography.settingDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                uiState.errorMessage?.let { message ->
                    TeddErrorBanner(message = message)
                }

                metadata?.let {
                    TeddOptionGroup(
                        title = "Overview",
                        description = "File details and your current place.",
                    ) {
                        MetadataRow(label = "Name", value = it.location.displayName)
                        MetadataRow(label = "Location", value = it.location.sourceUri)
                        MetadataRow(label = "Format", value = it.format.displayName())
                        MetadataRow(label = "Size", value = formatSize(it.location.sizeBytes))
                        MetadataRow(label = "Pages", value = formatPageCount(it.pageCount))
                        MetadataRow(label = "Current page", value = formatPagePosition(uiState.pageIndex))
                    }
                }

                TeddOptionGroup(
                    title = "Reading stats",
                    description = "A quick summary of this document and your reading pace.",
                ) {
                    StatRow(label = "Reading time", value = formatDuration(uiState.stats?.activeMillis))
                    StatRow(label = "Reading pace", value = formatReadingPace(uiState.stats))
                    StatRow(label = "Characters", value = formatCount(metadata?.characterCount))
                    StatRow(label = "Words", value = formatCount(metadata?.wordCount))
                }

                TeddOptionGroup(
                    title = "Recent sessions",
                    description = "Latest reading sessions for this document.",
                ) {
                    if (uiState.sessions.isEmpty()) {
                        Text(
                            text = "No reading sessions yet.",
                            style = typography.settingDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        uiState.sessions.take(10).forEachIndexed { index, session ->
                            TeddListItem(
                                title = "Session ${index + 1}",
                                supportingText = formatDuration(session.activeMillis),
                            )
                        }
                    }
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
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        Text(
            text = label,
            style = typography.settingDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = typography.settingTitle,
        )
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    MetadataRow(label = label, value = value, modifier = modifier)
}

internal fun formatSize(sizeBytes: Long?): String {
    if (sizeBytes == null) return "Not available"
    if (sizeBytes < 1_024L) return "$sizeBytes B"
    val kilobytes = sizeBytes / 1_024f
    if (kilobytes < 1_024f) return "${formatDecimal(kilobytes)} KB"
    return "${formatDecimal(kilobytes / 1_024f)} MB"
}

internal fun formatDuration(activeMillis: Long?): String {
    if (activeMillis == null) return "Not available"
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

internal fun formatReadingPace(stats: ReadingStats?): String {
    if (stats == null || stats.activeMillis <= 0L || stats.wordsRead <= 0L) return "Not available"
    return "${stats.wordsPerMinute.roundToInt()} words/min"
}

internal fun formatCount(value: Long?): String = value?.toString() ?: "Not available"

internal fun formatPageCount(pageCount: Int?): String = pageCount?.toString() ?: "Not available"

internal fun formatPagePosition(pageIndex: PageIndex?): String {
    if (pageIndex == null || pageIndex.total <= 0) return "Not available"
    return "${pageIndex.current + 1} of ${pageIndex.total}"
}

private fun formatDecimal(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private fun DocumentFormat.displayName(): String = when (this) {
    DocumentFormat.TXT -> "TXT"
    DocumentFormat.PDF -> "PDF"
    DocumentFormat.EPUB -> "EPUB"
    DocumentFormat.UNKNOWN -> "Unknown format"
}

@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Composable
private fun DocumentInfoScreenPreview() {
    TeddReaderTheme {
        DocumentInfoScreen(
            uiState = DocumentInfoUiState(
                isLoading = false,
                metadata = DocumentMetadata(
                    id = DocumentId("preview"),
                    location = DocumentLocation(
                        "file:///preview/very/long/path/to/preview.txt",
                        "preview.txt",
                        sizeBytes = 42_500L,
                    ),
                    format = DocumentFormat.TXT,
                    addedAtEpochMillis = 0L,
                    pageCount = 10,
                    characterCount = 1_000L,
                    wordCount = 200L,
                ),
                pageIndex = PageIndex(current = 3, total = 10),
                stats = ReadingStats(
                    documentId = DocumentId("preview"),
                    activeMillis = 12_345L,
                    charactersRead = 1_000L,
                    wordsRead = 200L,
                ),
                sessions = listOf(
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
            scrollState = rememberScrollState(),
        )
    }
}
