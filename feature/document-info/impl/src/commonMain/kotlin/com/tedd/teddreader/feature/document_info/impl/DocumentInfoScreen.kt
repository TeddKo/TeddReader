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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReadingStats
import com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSection
import com.tedd.teddreader.core.ui.component.TeddSectionKind
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.roundToInt

/**
 * 문서 정보 화면의 상태 보유 진입점이다. Koin을 통해 [DocumentInfoViewModel]을 가져오고,
 * [documentId]가 바뀔 때마다 설명할 문서를 알린 뒤 상태를 비상태 [DocumentInfoScreen]에 넘긴다.
 *
 * @param documentId 설명할 문서다. 값이 바뀌면 [DocumentInfoViewModel.setDocument]를 다시 호출한다.
 * @param onBack 사용자가 화면을 떠나려고 할 때 호출된다.
 * @param modifier [DocumentInfoScreen]의 루트에 적용할 수정자다.
 * @param viewModel 화면의 뷰 모델이다. 기본적으로 Koin을 통해 가져온다.
 */
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

/**
 * [uiState]만으로 구동되는 문서 정보 화면의 비상태 레이아웃이다. 문서 메타데이터 개요,
 * 읽기 통계, 최근 읽기 세션을 표시한다.
 *
 * 개요와 세션 섹션은 산문형 레이블/값 목록으로 읽히므로 [TeddReaderBreakpoints.readableMaxWidth]로
 * 너비를 제한하고 [BoundedWidthContent]로 가운데에 배치한다. 반면 읽기 통계 섹션은 확장형
 * 중단점을 지난 뒤 그리드를 최대 4열로 배치할 공간이 필요한 유일한 블록이므로 더 넓은
 * [TeddReaderBreakpoints.collectionMaxWidth]를 사용한다([ReadingStatsContent] 참고). 따라서 바깥
 * [LazyColumn] 자체에는 최대 너비를 두지 않고, 필요한 섹션만 직접 너비를 제한한다.
 *
 * @param uiState 화면의 현재 데이터와 로딩 및 오류 상태다.
 * @param onBack 사용자가 뒤로 탐색 동작을 누를 때 호출된다.
 * @param listState 화면 콘텐츠 목록의 스크롤 상태다. 호출자가 스크롤 위치를 관찰하거나
 *   복원할 수 있도록 끌어올려져 있다.
 * @param modifier 화면 루트에 적용할 수정자다.
 * @param contentPadding 스크롤 콘텐츠 위아래에 적용하는 수직 패딩이다. 화면 자체의 시스템 바와
 *   상단 바 인셋에 추가된다. 수평 인셋은 각 [TeddSection]이 소유하므로 수평 성분은 무시한다.
 *   너비가 제한된 섹션은 [BoundedWidthContent]를 통해 이를 적용한다. null이면 양쪽 가장자리에
 *   테마의 screenPadding을 사용한다.
 */
@Composable
fun DocumentInfoScreen(
    uiState: DocumentInfoUiState,
    onBack: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.screenPadding)
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val metadata = uiState.metadata

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.document_info),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentPadding = PaddingValues(
                top = resolvedContentPadding.calculateTopPadding(),
                bottom = resolvedContentPadding.calculateBottomPadding(),
            ),
        ) {
            uiState.errorMessage?.let { message ->
                item {
                    BoundedWidthContent(maxWidth = breakpoints.readableMaxWidth) {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = message)
                        }
                    }
                }
            }

            if (uiState.isLoading) {
                item {
                    BoundedWidthContent(maxWidth = breakpoints.readableMaxWidth) {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddLoadingIndicator(message = stringResource(Res.string.loading_document_info))
                        }
                    }
                }
            } else {
                metadata?.let {
                    item {
                        BoundedWidthContent(maxWidth = breakpoints.readableMaxWidth) {
                            TeddText(
                                text = it.location.displayName,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = spacing.screenPadding),
                                style = typography.titleMedium,
                            )
                        }
                    }
                    item {
                        BoundedWidthContent(maxWidth = breakpoints.readableMaxWidth) {
                            TeddSection(
                                kind = TeddSectionKind.Form,
                                title = stringResource(Res.string.overview),
                                description = stringResource(Res.string.overview_description),
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
                }

                item {
                    BoundedWidthContent(maxWidth = breakpoints.collectionMaxWidth) {
                        TeddSection(
                            kind = TeddSectionKind.Collection,
                            title = stringResource(Res.string.reading_stats),
                            description = stringResource(Res.string.reading_stats_description),
                        ) {
                            ReadingStatsContent(
                                readingTime = formatDuration(uiState.stats?.activeMillis, unavailable = stringResource(Res.string.not_available)),
                                readingPace = formatReadingPace(uiState.stats, unavailable = stringResource(Res.string.not_available), suffix = stringResource(Res.string.reading_pace_suffix)),
                                characters = formatCount(metadata?.characterCount, unavailable = stringResource(Res.string.not_available)),
                                words = formatCount(metadata?.wordCount, unavailable = stringResource(Res.string.not_available)),
                            )
                        }
                    }
                }

                item {
                    BoundedWidthContent(maxWidth = breakpoints.readableMaxWidth) {
                        TeddSection(
                            kind = TeddSectionKind.Collection,
                            title = stringResource(Res.string.recent_sessions),
                            description = stringResource(Res.string.recent_sessions_description),
                        ) {
                            if (uiState.sessions.isEmpty()) {
                                TeddText(
                                    text = stringResource(Res.string.no_reading_sessions),
                                    style = typography.settingDescription,
                                    color = colors.onSurfaceVariant,
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

/**
 * [content]의 너비를 [maxWidth]로 제한하고 전체 [LazyColumn] 너비 안에서 가운데에 배치한다.
 * 따라서 제한된 열로 읽히는 개요, 문서 이름 행, 세션 목록 또는 자체적으로 더 넓은
 * [TeddReaderBreakpoints.collectionMaxWidth]를 쓰는 읽기 통계 그리드는 바깥의 확장 창이 실제로
 * 얼마나 넓든 해당 너비를 유지한다.
 *
 * @param maxWidth 콘텐츠의 최대 너비다. 보통 산문형 블록에는
 *   [TeddReaderBreakpoints.readableMaxWidth], 더 넓은 공간이 필요한 그리드에는
 *   [TeddReaderBreakpoints.collectionMaxWidth]를 사용한다.
 * @param modifier 바깥쪽 전체 너비 가운데 정렬 Box에 적용할 수정자다.
 * @param content 너비를 제한할 콘텐츠다.
 */
@Composable
private fun BoundedWidthContent(
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth()) {
            content()
        }
    }
}

/**
 * 4개의 읽기 통계 값을 레이블/값 쌍으로 이루어진 그리드에 배치한다. 열 수는
 * [TeddReaderBreakpoints]를 따른다. [TeddReaderBreakpoints.compact] 미만에서는 1열,
 * 그 지점부터 [TeddReaderBreakpoints.expanded] 미만까지는 2열, 그 이상에서는 4열을 사용하는
 * 것이 문서 정보 화면의 적응형 통계 규약이다.
 *
 * @param readingTime 형식화된 총 활성 읽기 시간이다.
 * @param readingPace 형식화된 분당 단어 수 읽기 속도다.
 * @param characters 형식화된 문자 수다.
 * @param words 형식화된 단어 수다.
 */
@Composable
private fun ReadingStatsContent(
    readingTime: String,
    readingPace: String,
    characters: String,
    words: String,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val stats = listOf(
        stringResource(Res.string.reading_time) to readingTime,
        stringResource(Res.string.reading_pace) to readingPace,
        stringResource(Res.string.characters) to characters,
        stringResource(Res.string.words) to words,
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth < breakpoints.compact -> 1
            maxWidth < breakpoints.expanded -> 2
            else -> 4
        }
        Column(verticalArrangement = Arrangement.spacedBy(spacing.itemGap)) {
            stats.chunked(columns).forEach { rowItems ->
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
    }
}

/**
 * 문서 정보 화면의 개요와 통계 섹션에 표시하는 레이블이 붙은 값 하나다. 작은 캡션을 값 위에
 * 배치하며, 해당 섹션의 모든 행이 이 레이아웃을 공유한다.
 *
 * @param label 필드의 캡션이다.
 * @param value 이미 형식화된 필드 값이다.
 * @param modifier 행 루트에 적용할 수정자다.
 */
@Composable
private fun MetadataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        TeddText(text = label, style = typography.settingDescription, color = colors.onSurfaceVariant)
        TeddText(text = value, style = typography.settingTitle)
    }
}

/**
 * 개요 섹션의 표시 방식에 맞춰 바이트 수를 렌더링한다. 1 KB 미만은 바이트를 그대로 표시하고,
 * 그 이상은 [formatDecimal]로 소수점 한 자리까지 반올림한 KB 또는 MB로 표시한다. 테스트가 이
 * 함수를 직접 호출할 수 있고 화면은 현지화된 문자열을 전달할 수 있도록 영어 기본값을 가진
 * [unavailable]을 매개변수로 받는다.
 *
 * @param sizeBytes 형식화할 크기다. 알 수 없으면 null이다.
 * @param unavailable [sizeBytes]가 null일 때 표시할 텍스트다.
 */
internal fun formatSize(sizeBytes: Long?, unavailable: String = "Not available"): String {
    if (sizeBytes == null) return unavailable
    if (sizeBytes < 1_024L) return "$sizeBytes B"
    val kilobytes = sizeBytes / 1_024f
    if (kilobytes < 1_024f) return "${formatDecimal(kilobytes)} KB"
    return "${formatDecimal(kilobytes / 1_024f)} MB"
}

/**
 * 개요와 통계 섹션의 표시 방식에 맞춰 활성 읽기 시간을 렌더링한다. 1시간 이상이면 시간과 분,
 * 1분 이상이면 분과 초, 그 외에는 초만 표시하며 한 번에 단위를 2개보다 많이 표시하지 않는다.
 * [unavailable]이 하드코딩된 문자열이 아니라 매개변수인 이유는 [formatSize]를 참고한다.
 *
 * @param activeMillis 형식화할 시간이다. 알 수 없으면 null이다.
 * @param unavailable [activeMillis]가 null일 때 표시할 텍스트다.
 */
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

/**
 * 문서의 읽기 속도를 분당 단어 수로 렌더링한다. 읽은 시간이 없거나 읽은 단어가 없어 아직
 * 의미 있는 값을 표시할 수 없으면 [unavailable]을 사용한다. 어느 경우든 계산하면 "데이터 없음"이
 * 아니라 속도 0이 되기 때문이다.
 *
 * @param stats 속도를 계산할 읽기 합계다. 아직 없으면 null이다.
 * @param unavailable 속도를 계산할 수 없을 때 표시할 텍스트다.
 * @param suffix 호출자가 단위를 현지화할 수 있도록 숫자 뒤에 붙이는 텍스트다.
 */
internal fun formatReadingPace(
    stats: ReadingStats?,
    unavailable: String = "Not available",
    suffix: String = " words/min",
): String {
    if (stats == null || stats.activeMillis <= 0L || stats.wordsRead <= 0L) return unavailable
    return "${stats.wordsPerMinute.roundToInt()}$suffix"
}

/**
 * 선택적인 수치(읽은 문자 또는 단어)를 일반 텍스트로 렌더링한다. 아직 알 수 없으면
 * [unavailable]을 사용한다.
 *
 * @param value 형식화할 수치다. 알 수 없으면 null이다.
 * @param unavailable [value]가 null일 때 표시할 텍스트다.
 */
internal fun formatCount(value: Long?, unavailable: String = "Not available"): String = value?.toString() ?: unavailable

/**
 * 선택적인 페이지 수를 일반 텍스트로 렌더링한다. 아직 문서를 측정한 결과가 없으면
 * [unavailable]을 사용한다.
 *
 * @param pageCount 형식화할 페이지 수다. 알 수 없으면 null이다.
 * @param unavailable [pageCount]가 null일 때 표시할 텍스트다.
 */
internal fun formatPageCount(pageCount: Int?, unavailable: String = "Not available"): String = pageCount?.toString() ?: unavailable

/**
 * 독자가 마지막으로 읽던 위치를 현재 페이지 번호와 전체 페이지 번호를 이어붙인 형태로
 * 렌더링한다. 저장된 위치가 없거나 문서가 아직 페이지로 측정되지 않았으면 [unavailable]을
 * 사용한다. 그렇지 않으면 [PageIndex.total]이 0일 때 "1 of 0"으로 표시되기 때문이다.
 *
 * @param pageIndex 형식화할 저장 위치다. 없으면 null이다.
 * @param unavailable [pageIndex]가 null이거나 아직 페이지가 없을 때 표시할 텍스트다.
 * @param separator 호출자가 현지화할 수 있도록 현재 페이지 번호와 전체 페이지 번호 사이에
 *   넣는 텍스트다.
 */
internal fun formatPagePosition(
    pageIndex: PageIndex?,
    unavailable: String = "Not available",
    separator: String = " of ",
): String {
    if (pageIndex == null || pageIndex.total <= 0) return unavailable
    return "${pageIndex.current + 1}$separator${pageIndex.total}"
}

/**
 * [value]를 소수점 한 자리로 반올림하고 뒤따르는 `.0`을 제거한다. 따라서 5.0 MB 같은 크기는
 * "5.0 MB"가 아니라 "5 MB"로 표시되고 5.3 MB는 소수부를 유지한다.
 */
private fun formatDecimal(value: Float): String {
    val rounded = (value * 10).roundToInt() / 10f
    return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
}

/**
 * 개요 섹션이 이 형식에 표시하는 짧은 레이블이다. [DocumentFormat.UNKNOWN]이면 [unknown]을
 * 표시한다. 위 `format*` 함수와 같은 패턴으로 [unknown]에 영어 기본값을 두어 테스트가 영어
 * 텍스트를 직접 검증할 수 있게 한다.
 *
 * @param unknown [DocumentFormat.UNKNOWN]에 표시할 텍스트다.
 */
private fun DocumentFormat.displayName(unknown: String = "Unknown format"): String = when (this) {
    DocumentFormat.TXT -> "TXT"
    DocumentFormat.PDF -> "PDF"
    DocumentFormat.EPUB -> "EPUB"
    DocumentFormat.CBZ -> "CBZ"
    DocumentFormat.IMAGE -> "Image"
    DocumentFormat.UNKNOWN -> unknown
}

/**
 * 대표 샘플 데이터로 렌더링하는 [DocumentInfoScreen]의 디자인 타임 미리보기다. 좁은 단일 열
 * 통계 레이아웃, 더 넓은 2열 레이아웃, 개요와 세션 섹션에 적용되는 읽기 너비 제한을 모두
 * 확인하도록 3가지 너비를 사용한다.
 */
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
