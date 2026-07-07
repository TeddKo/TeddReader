package com.tedd.teddreader.feature.document_info.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddInfoRow
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddSurface
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DocumentInfoRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DocumentInfoViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    DocumentInfoScreen(
        uiState = uiState,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun DocumentInfoScreen(
    uiState: DocumentInfoUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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

    TeddSurface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                TeddButton(text = "Back", onClick = onBack)
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xxSmall)) {
                    Text(
                        text = "Document info",
                        style = typography.headlineSmall,
                    )
                    Text(
                        text = metadata?.location?.displayName ?: uiState.documentId,
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            uiState.errorMessage?.let { message ->
                TeddErrorBanner(message = message)
            }

            metadata?.let {
                TeddCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(spacing.small),
                    ) {
                        Text(
                            text = "Overview",
                            style = typography.titleMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(spacing.small)) {
                            TeddChip(text = it.format.name)
                            TeddChip(text = "${it.pageCount ?: 0} pages")
                            TeddChip(text = uiState.pageIndex?.let { pageIndex -> "${pageIndex.current + 1}/${pageIndex.total}" } ?: "Current page -")
                            TeddChip(text = "${it.location.sizeBytes} B")
                        }
                        TeddInfoRow(
                            label = "Name",
                            value = it.location.displayName,
                        )
                        TeddInfoRow(
                            label = "Location",
                            value = it.location.sourceUri,
                        )
                        TeddInfoRow(
                            label = "Format",
                            value = it.format.name,
                        )
                        TeddInfoRow(
                            label = "Size",
                            value = "${it.location.sizeBytes} B",
                        )
                        TeddInfoRow(
                            label = "Pages",
                            value = it.pageCount?.toString() ?: "-",
                        )
                        TeddInfoRow(
                            label = "Current page",
                            value = uiState.pageIndex?.let { pageIndex -> "${pageIndex.current + 1} / ${pageIndex.total}" } ?: "-",
                        )
                    }
                }
            }

            TeddCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    Text(
                        text = "Reading stats",
                        style = typography.titleMedium,
                    )
                    StatGrid(
                        stats = listOf(
                            "Reading time" to (uiState.stats?.activeMillis?.toString() ?: "-"),
                            "Words/min" to (uiState.stats?.wordsPerMinute?.toInt()?.toString() ?: "-"),
                            "Characters" to (metadata?.characterCount?.toString() ?: "-"),
                            "Words" to (metadata?.wordCount?.toString() ?: "-"),
                        ),
                    )
                }
            }

            TeddCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    Text(
                        text = "Recent sessions",
                        style = typography.titleMedium,
                    )
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
                                supportingText = "${session.activeMillis} ms active",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatGrid(
    stats: List<Pair<String, String>>,
) {
    val spacing = teddReaderSpacing()

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        stats.forEach { (label, value) ->
            StatCard(
                label = label,
                value = value,
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    TeddCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
        ) {
            Text(
                text = label,
                style = typography.settingDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = typography.statValue,
            )
        }
    }
}

@Preview
@Composable
private fun DocumentInfoScreenPreview() {
    TeddReaderTheme {
        DocumentInfoScreen(
            uiState = DocumentInfoUiState(
                isLoading = false,
                metadata = DocumentMetadata(
                    id = DocumentId("preview"),
                    location = DocumentLocation("file:///preview.txt", "preview.txt", sizeBytes = 42L),
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
        )
    }
}
