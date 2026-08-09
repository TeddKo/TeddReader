package com.tedd.teddreader.feature.document_info.impl

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.teddString
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
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            TeddLoadingIndicator(message = teddString("Loading document info", "문서 정보를 불러오는 중"))
        }
        return
    }

    val metadata = uiState.metadata

    TeddScaffold(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        topBar = {
            TeddTopBar(
                title = teddString("Document info", "문서 정보"),
                navigationIcon = {
                    TeddIconButton(
                        onClick = onBack,
                        contentDescription = teddString("Back", "뒤로"),
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
                    title = teddString("Overview", "개요"),
                    description = teddString("File details and your current place.", "파일 정보와 현재 위치입니다."),
                ) {
                    MetadataRow(label = teddString("Name", "이름"), value = it.location.displayName)
                    MetadataRow(label = teddString("Location", "위치"), value = it.location.sourceUri)
                    MetadataRow(label = teddString("Format", "형식"), value = it.format.displayName(unknown = teddString("Unknown format", "알 수 없는 형식")))
                    MetadataRow(label = teddString("Size", "크기"), value = formatSize(it.location.sizeBytes, unavailable = teddString("Not available", "정보 없음")))
                    MetadataRow(label = teddString("Pages", "페이지"), value = formatPageCount(it.pageCount, unavailable = teddString("Not available", "정보 없음")))
                    MetadataRow(label = teddString("Current page", "현재 페이지"), value = formatPagePosition(uiState.pageIndex, unavailable = teddString("Not available", "정보 없음"), separator = teddString(" of ", " / ")))
                }
            }

            TeddOptionGroup(
                title = teddString("Reading stats", "읽기 통계"),
                description = teddString("A quick summary of this document and your reading pace.", "이 문서와 읽기 속도의 요약입니다."),
            ) {
                MetadataRow(label = teddString("Reading time", "읽은 시간"), value = formatDuration(uiState.stats?.activeMillis, unavailable = teddString("Not available", "정보 없음")))
                MetadataRow(label = teddString("Reading pace", "읽기 속도"), value = formatReadingPace(uiState.stats, unavailable = teddString("Not available", "정보 없음"), suffix = teddString(" words/min", " 단어/분")))
                MetadataRow(label = teddString("Characters", "문자 수"), value = formatCount(metadata?.characterCount, unavailable = teddString("Not available", "정보 없음")))
                MetadataRow(label = teddString("Words", "단어 수"), value = formatCount(metadata?.wordCount, unavailable = teddString("Not available", "정보 없음")))
            }

            TeddOptionGroup(
                title = teddString("Recent sessions", "최근 세션"),
                description = teddString("Latest reading sessions for this document.", "이 문서의 최근 읽기 세션입니다."),
            ) {
                if (uiState.sessions.isEmpty()) {
                    Text(
                        text = teddString("No reading sessions yet.", "아직 읽기 세션이 없습니다."),
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    uiState.sessions.take(10).forEachIndexed { index, session ->
                        TeddListItem(
                            title = teddString("Session ${index + 1}", "세션 ${index + 1}"),
                            supportingText = formatDuration(session.activeMillis),
                        )
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
    return if (rounded % 1f == 0f) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private fun DocumentFormat.displayName(unknown: String = "Unknown format"): String = when (this) {
    DocumentFormat.TXT -> "TXT"
    DocumentFormat.PDF -> "PDF"
    DocumentFormat.EPUB -> "EPUB"
    DocumentFormat.UNKNOWN -> unknown
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
