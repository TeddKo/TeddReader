package com.tedd.teddreader.feature.search.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSearchField
import com.tedd.teddreader.core.ui.component.TeddSection
import com.tedd.teddreader.core.ui.component.TeddSectionKind
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.extension.clearFocusOnBackgroundTap
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * 한 문서의 문서 내 검색을 위해 [SearchViewModel]을 [SearchScreen]에 연결하는 진입점이다.
 * 아래의 [SearchScreen]과 마찬가지로 이 컴포저블은 상태와 콜백을 뷰 모델에 그대로 전달한다.
 * [SearchUiState]를 수집하고 모든 사용자 동작을 뷰 모델 호출로 돌려보내며, 임시 값조차 자체
 * 검색 상태로 보관하지 않는다. 리더나 저장된 위치의 경로 화면과 달리 여기에는 진행 중인
 * 값을 보관할 필요가 없다. 검색어 입력에는 슬라이더 드래그나 메모 편집처럼 진행 중인
 * 제스처와 확정된 값을 구분할 필요가 없으므로, 검색 필드의 모든 키 입력을
 * [SearchViewModel.updateQuery]를 통해 [SearchUiState.query]에 바로 반영한다.
 *
 * @param documentId 검색할 문서로, 변경되면 [SearchViewModel.setDocument]를 다시 실행한다.
 * @param onBack 사용자가 검색 화면에서 나가려 할 때 호출한다.
 * @param onResultClick 사용자가 검색 결과를 탭해 해당 위치로 이동할 때 그 위치와 함께 호출한다.
 * @param modifier 생성되는 [SearchScreen]에 적용할 수정자이다.
 * @param viewModel 검색 화면의 뷰 모델로, 기본값은 Koin이 제공하는 인스턴스이다.
 */
@Composable
fun SearchRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    onResultClick: (ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    SearchScreen(
        uiState = uiState,
        onQueryChange = viewModel::updateQuery,
        onSearchClick = viewModel::search,
        onBack = onBack,
        onResultClick = onResultClick,
        listState = listState,
        modifier = modifier,
    )
}

/**
 * 스크롤 목록 상단에 검색 폼을 배치하고, 현재 [uiState]가 나타내는 검색 미지원, 오류, 로딩,
 * 빈 검색어, 결과 없음, 검색 결과 상태 중 하나를 그 뒤에 표시하는 문서 내 검색 화면이다. 이
 * 컴포저블은 상태와 콜백을 뷰 모델에 그대로 전달한다. 표시하는 모든 값은 [uiState]나 다른
 * 매개변수에서 가져오며 자체 검색 상태를 보관하지 않는다. `isFieldEnabled`, `canSearch`,
 * `resultContentPadding`도 저장된 상태가 아니라 호출할 때마다 매개변수에서 새로 파생한다.
 *
 * 각 블록은 하나의 [TeddSection]이므로 상태는 구조적으로 상호 배타적이다. 검색 미지원, 오류,
 * 로딩, 빈 검색어, 결과 없음 분기 중 정확히 하나가 [TeddSectionKind.Status] 섹션을 제공하고,
 * 검색 결과 분기는 그 대신 [TeddSectionKind.Collection] 섹션을 제공한다. 검색 결과 제목은
 * 본문이 비어 있는 [TeddSectionKind.Collection] 섹션으로 표시하므로 본문이 없어 `fullBleed`에
 * 따른 비용이 없다. 결과 행은 해당 섹션의 본문이 아니라 바로 뒤에 오는 독립적인 평면
 * [LazyColumn] 항목으로 둔다. 따라서 결과는 실제 행 단위 지연 처리를 유지하고, 각 행은
 * `resultContentPadding`을 통해 화면의 가로 여백을 직접 제공한다. 이 구조로 각 행이 그리는
 * 구분선과 리플이 섹션 간격으로 끊기지 않고 제한된 열 너비 전체를 채워, 검색 화면의 연속
 * 결과 계약을 지킨다.
 *
 * 콘텐츠 [Box]에는 `clearFocusOnBackgroundTap`을 적용한다. 너비가 제한된 목록을 넓은 창에서
 * 표시할 때의 옆 여백이나 마지막 결과 아래 빈 공간처럼 각 결과 행의 클릭 가능 영역 밖을
 * 탭하면 포커스를 해제하고 검색 필드가 띄운 소프트 키보드를 닫는다. 필드나 결과 행을 탭하면
 * 해당 요소의 클릭 처리가 먼저 소비하므로 호출자가 의존하는 탭을 가로채지 않는다.
 *
 * @param uiState 뷰 모델이 발행한 검색 화면의 현재 상태이다.
 * @param onQueryChange 사용자가 검색 필드에 입력할 때 호출한다.
 * @param onSearchClick 필드 자체 동작이나 검색 버튼으로 사용자가 검색을 요청할 때 호출한다.
 * @param onBack 사용자가 화면에서 나가려 할 때 호출한다.
 * @param onResultClick 사용자가 검색 결과를 탭할 때 그 결과의 위치와 함께 호출한다.
 * @param listState 검색 폼과 결과 목록의 스크롤 상태이다.
 * @param modifier 스캐폴드에 적용할 수정자이다.
 * @param contentPadding 목록 콘텐츠의 위아래에 적용할 세로 여백이다. 가로 여백은 각
 * [TeddSection]과 결과 행 자체가 담당하므로 모든 가로 성분을 무시한다. null이면 양쪽 끝에
 * 테마의 screenPadding을 사용한다.
 */
@Composable
fun SearchScreen(
    uiState: SearchUiState,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    onBack: () -> Unit,
    onResultClick: (ReaderLocation) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.screenPadding)
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val isFieldEnabled = !uiState.isSearchUnsupported
    val canSearch = isFieldEnabled && uiState.query.isNotBlank() && !uiState.isLoading
    val resultContentPadding = PaddingValues(
        horizontal = spacing.screenPadding,
        vertical = spacing.small,
    )

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.search_in_document),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .imePadding()
                .clearFocusOnBackgroundTap(focusManager = LocalFocusManager.current),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .widthIn(max = breakpoints.readableMaxWidth)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    top = resolvedContentPadding.calculateTopPadding(),
                    bottom = resolvedContentPadding.calculateBottomPadding(),
                ),
            ) {
                item {
                    TeddSection(kind = TeddSectionKind.Form) {
                        SearchForm(
                            query = uiState.query,
                            isFieldEnabled = isFieldEnabled,
                            canSearch = canSearch,
                            onQueryChange = onQueryChange,
                            onSearchClick = onSearchClick,
                        )
                    }
                }

                when {
                    uiState.isSearchUnsupported -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = stringResource(Res.string.search_pdf_unsupported))
                        }
                    }
                    uiState.errorMessage != null -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = uiState.errorMessage)
                        }
                    }
                    uiState.isLoading -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddLoadingIndicator(message = stringResource(Res.string.search_loading))
                        }
                    }
                    uiState.query.isBlank() -> Unit
                    uiState.results.isEmpty() -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.xxSmall)) {
                                TeddText(text = stringResource(Res.string.search_no_results_title), style = typography.titleMedium)
                                TeddText(
                                    text = stringResource(Res.string.search_no_results_description),
                                    style = typography.settingDescription,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    else -> {
                        item {
                            TeddSection(
                                kind = TeddSectionKind.Collection,
                                title = stringResource(Res.string.search_matches_count, uiState.results.size),
                                fullBleed = true,
                            ) {}
                        }
                        items(uiState.results) { result ->
                            TeddListItem(
                                title = result.snippet,
                                supportingText = buildSearchSupportingText(result),
                                onClick = { onResultClick(result.location) },
                                singleClick = true,
                                contentPadding = resultContentPadding,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 설명 문구 아래에 반응형 레이아웃을 배치한 검색 입력 영역이다. 좁은 컨테이너에서는 검색
 * 필드 위아래로 전체 너비 버튼을 쌓고, 넓은 컨테이너에서는 둘을 나란히 배치한다.
 * [TeddReaderBreakpoints.compactControlWidth][com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints.compactControlWidth]를
 * 기준으로 레이아웃을 전환한다.
 *
 * @param query 검색 필드의 현재 텍스트이다.
 * @param isFieldEnabled 필드가 입력을 받는지 여부이다. 열린 문서가 검색을 지원하지 않으면
 * false이다.
 * @param canSearch 지금 검색을 실제로 실행할 수 있는지 여부로, 검색 버튼의 활성화를 결정한다.
 * @param onQueryChange 사용자가 검색 필드에 입력할 때 호출한다.
 * @param onSearchClick 사용자가 검색 버튼이나 필드 자체의 검색 동작을 탭할 때 호출한다.
 * @param modifier 폼의 루트 열에 적용할 수정자이다.
 */
@Composable
private fun SearchForm(
    query: String,
    isFieldEnabled: Boolean,
    canSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        TeddText(
            text = stringResource(Res.string.search_find_passage_description),
            style = typography.settingDescription,
            color = colors.onSurfaceVariant,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < breakpoints.compactControlWidth) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    SearchField(
                        query = query,
                        isFieldEnabled = isFieldEnabled,
                        canSearch = canSearch,
                        onQueryChange = onQueryChange,
                        onSearchClick = onSearchClick,
                    )
                    TeddButton(
                        text = stringResource(Res.string.search),
                        onClick = onSearchClick,
                        enabled = canSearch,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchField(
                        query = query,
                        isFieldEnabled = isFieldEnabled,
                        canSearch = canSearch,
                        onQueryChange = onQueryChange,
                        onSearchClick = onSearchClick,
                        modifier = Modifier.weight(1f),
                    )
                    TeddButton(
                        text = stringResource(Res.string.search),
                        onClick = onSearchClick,
                        enabled = canSearch,
                        modifier = Modifier.defaultMinSize(minHeight = spacing.rowHeight),
                    )
                }
            }
        }
    }
}

/**
 * 지울 텍스트가 있을 때만 지우기 버튼을 표시하는 [TeddSearchField] 기반 검색 텍스트 필드이다.
 *
 * @param query 필드의 현재 텍스트이다.
 * @param isFieldEnabled 필드와 지우기 버튼이 입력을 받는지 여부이다.
 * @param canSearch Enter 또는 검색 동작이 실제로 검색을 실행해야 하는지 여부이다.
 * @param onQueryChange 사용자가 입력할 때 호출하며, 지우기 버튼을 탭하면 빈 문자열과 함께
 * 호출한다.
 * @param onSearchClick 필드 자체의 검색 동작이 발생하고 [canSearch]가 true일 때 호출한다.
 * @param modifier 필드에 적용할 수정자이다.
 */
@Composable
private fun SearchField(
    query: String,
    isFieldEnabled: Boolean,
    canSearch: Boolean,
    onQueryChange: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddSearchField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = stringResource(Res.string.search_text_placeholder),
        enabled = isFieldEnabled,
        onSearch = { if (canSearch) onSearchClick() },
        onClearClick = { onQueryChange("") },
        clearDescription = stringResource(Res.string.clear_search_query),
    )
}

/**
 * 검색 결과의 스니펫 아래에 표시할 보조 텍스트를 만든다. 결과를 찾은 섹션과 정확한 위치를
 * 현지화한 설명([ReaderLocation.displayLabel] 참고)을 각각 별도 줄에 배치한다. 장 구조가 없는
 * 문서처럼 결과에 섹션 제목이 없으면 섹션 줄을 생략한다.
 *
 * @param result 보조 텍스트를 만들 검색 결과이다.
 * @return 섹션 제목이 있으면 해당 제목과 위치 설명을 담은 두 줄, 없으면 위치 설명 한 줄이다.
 */
@Composable
private fun buildSearchSupportingText(result: SearchResult): String = buildList {
    result.sectionTitle?.takeIf { it.isNotBlank() }?.let(::add)
    add(result.location.displayLabel())
}.joinToString(separator = "\n")

/**
 * 검색 결과의 보조 텍스트로 표시할, 이 위치가 가리키는 곳에 대한 현지화된 설명이다. 세 형식에
 * 하나의 일반 레이블을 쓰지 않고 각 [ReaderLocation] 변형에 맞는 용어를 사용한다. PDF에는
 * 페이지 번호, 일반 텍스트에는 원시 텍스트 오프셋, EPUB에는 spine 섹션을 사용한다. 저장된
 * 위치 화면의 `displayLabel`과 같은 방식이지만, 이 프로젝트에서는 어떤 feature 모듈도 다른
 * feature의 api나 impl에 의존할 수 없으므로 공유하지 않고 별도 사본으로 둔다.
 *
 * @receiver 설명할 위치이다.
 */
@Composable
private fun ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.reader_location_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.reader_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.reader_location_epub_section, spineIndex + 1)
}

/**
 * 샘플 검색 결과 하나로 [SearchScreen]을 세 가지 너비에서 표시해 화면 콘텐츠가 그릴 수 있는
 * 좁은, 기본, 넓은 레이아웃을 확인하는 미리보기이다.
 */
@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 720)
@Composable
private fun SearchScreenPreview() {
    TeddReaderTheme {
        SearchScreen(
            uiState = SearchUiState(
                documentId = "preview",
                query = "reader",
                results = persistentListOf(
                    SearchResult(
                        documentId = DocumentId("preview"),
                        query = "reader",
                        location = ReaderLocation.TextOffset(10L),
                        snippet = "Reader search result preview with a longer snippet that still wraps cleanly.",
                        sectionTitle = "Opening section",
                        range = TextRange(10L, 16L),
                    ),
                ),
            ),
            onQueryChange = {},
            onSearchClick = {},
            onBack = {},
            onResultClick = {},
            listState = rememberLazyListState(),
        )
    }
}
