package com.tedd.teddreader.feature.home.impl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderIconography
import com.tedd.teddreader.core.designsystem.teddReaderMotion
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddAlertDialog
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddCard
import com.tedd.teddreader.core.ui.component.TeddChip
import com.tedd.teddreader.core.ui.component.TeddEmptyState
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddFullScreenLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSection
import com.tedd.teddreader.core.ui.component.TeddSectionKind
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.add_documents
import com.tedd.teddreader.core.ui.generated.resources.add_to_favorites
import com.tedd.teddreader.core.ui.generated.resources.all
import com.tedd.teddreader.core.ui.generated.resources.back
import com.tedd.teddreader.core.ui.generated.resources.cancel
import com.tedd.teddreader.core.ui.generated.resources.cloud_documents
import com.tedd.teddreader.core.ui.generated.resources.delete
import com.tedd.teddreader.core.ui.generated.resources.document_pages
import com.tedd.teddreader.core.ui.generated.resources.favorite_documents_count
import com.tedd.teddreader.core.ui.generated.resources.favorite_documents_single
import com.tedd.teddreader.core.ui.generated.resources.favorites
import com.tedd.teddreader.core.ui.generated.resources.files_in_folder_count
import com.tedd.teddreader.core.ui.generated.resources.folder_row_description
import com.tedd.teddreader.core.ui.generated.resources.folders
import com.tedd.teddreader.core.ui.generated.resources.format
import com.tedd.teddreader.core.ui.generated.resources.google_drive
import com.tedd.teddreader.core.ui.generated.resources.google_drive_description
import com.tedd.teddreader.core.ui.generated.resources.home_add_documents_description
import com.tedd.teddreader.core.ui.generated.resources.home_loading_recent_documents
import com.tedd.teddreader.core.ui.generated.resources.home_no_documents_description
import com.tedd.teddreader.core.ui.generated.resources.home_no_documents_title
import com.tedd.teddreader.core.ui.generated.resources.home_no_matching_documents
import com.tedd.teddreader.core.ui.generated.resources.home_no_matching_documents_description
import com.tedd.teddreader.core.ui.generated.resources.home_selection_count
import com.tedd.teddreader.core.ui.generated.resources.library
import com.tedd.teddreader.core.ui.generated.resources.library_empty_folder_description
import com.tedd.teddreader.core.ui.generated.resources.library_empty_folder_title
import com.tedd.teddreader.core.ui.generated.resources.library_preview_description
import com.tedd.teddreader.core.ui.generated.resources.local_documents
import com.tedd.teddreader.core.ui.generated.resources.masthead_description
import com.tedd.teddreader.core.ui.generated.resources.recent
import com.tedd.teddreader.core.ui.generated.resources.recent_reading
import com.tedd.teddreader.core.ui.generated.resources.recent_reading_description
import com.tedd.teddreader.core.ui.generated.resources.remove_from_favorites
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_message
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_multiple_message
import com.tedd.teddreader.core.ui.generated.resources.remove_from_library_title
import com.tedd.teddreader.core.ui.generated.resources.select_document
import com.tedd.teddreader.core.ui.generated.resources.select_files
import com.tedd.teddreader.core.ui.generated.resources.select_files_description
import com.tedd.teddreader.core.ui.generated.resources.select_folder
import com.tedd.teddreader.core.ui.generated.resources.select_folder_description
import com.tedd.teddreader.core.ui.generated.resources.settings
import com.tedd.teddreader.core.ui.generated.resources.show_all
import com.tedd.teddreader.core.ui.generated.resources.sort
import com.tedd.teddreader.core.ui.generated.resources.title
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.system.rememberDisplayFold
import com.tedd.teddreader.feature.home.impl.component.DocumentCard
import com.tedd.teddreader.feature.home.impl.component.FolderCoverCard
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * 홈 화면의 상태 보유 진입점이다. reader의 `ReaderRouteScreen`과 같은 방식으로 [HomeViewModel]의 상태와
 * 동작을 상태 없는 [HomeScreen]에 연결한다.
 *
 * @param modifier [HomeScreen] root에 적용할 modifier.
 * @param importMessage 앱의 다른 곳에서 방금 완료된 가져오기(예: file picker 결과)의 오류 메시지. 다음 상태
 *   갱신이 교체할 때까지 뷰 모델 자체 오류 대신 표시한다.
 * @param onOpenFilesClick 사용자가 파일을 선택해 문서를 추가할 때 호출한다.
 * @param onOpenFolderClick 사용자가 폴더를 선택해 문서를 추가할 때 호출한다.
 * @param onOpenGoogleDriveClick 사용자가 Google Drive에서 문서를 추가할 때 호출한다. 플랫폼에서 지원하지
 *   않는 곳에서는 null로 옵션 자체를 숨긴다.
 * @param onSettingsClick 설정 동작을 누를 때 호출한다.
 * @param onDocumentClick 사용자가 열려고 누른 문서 id를 전달해 호출한다.
 * @param onOpenLibraryClick 사용자가 미리보기 너머의 전체 라이브러리를 보려고 할 때 호출한다.
 * @param onOpenLibraryFolderClick 사용자가 라이브러리 폴더를 열 때 폴더 id를 전달해 호출한다.
 * @param viewModel 화면의 뷰 모델. 기본값은 Koin에서 가져온다.
 */
@Composable
fun HomeRouteScreen(
    modifier: Modifier = Modifier,
    importMessage: String? = null,
    onOpenFilesClick: () -> Unit = {},
    onOpenFolderClick: () -> Unit = {},
    onOpenGoogleDriveClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit = {},
    onDocumentClick: (DocumentId) -> Unit = {},
    onOpenLibraryClick: () -> Unit = {},
    onOpenLibraryFolderClick: (String) -> Unit = {},
    viewModel: HomeViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberLazyListState()

    HomeScreen(
        uiState = uiState.copy(
            unsupportedFormatMessage = importMessage ?: uiState.unsupportedFormatMessage,
        ),
        onOpenFilesClick = onOpenFilesClick,
        onOpenFolderClick = onOpenFolderClick,
        onOpenGoogleDriveClick = onOpenGoogleDriveClick,
        onSettingsClick = onSettingsClick,
        onDocumentClick = onDocumentClick,
        onOpenLibraryClick = onOpenLibraryClick,
        onOpenLibraryFolderClick = onOpenLibraryFolderClick,
        onDocumentBookmarkChange = viewModel::setDocumentBookmarked,
        onSelectionBookmarkChange = viewModel::setDocumentsBookmarked,
        onDeleteDocuments = viewModel::deleteDocuments,
        onSortChange = viewModel::updateSort,
        onFormatFilterChange = viewModel::updateFormatFilter,
        onLoadCover = viewModel::loadCover,
        scrollState = scrollState,
        modifier = modifier,
    )
}

/**
 * 홈 화면 UI다. masthead, 즐겨찾기/최근 읽기 carousel, 라이브러리 미리보기 그리드와 그 위에 표시되는
 * 문서 추가/삭제 확인 dialog 및 다중 선택 top bar를 구성한다.
 *
 * 화면의 스크롤 영역은 모든 child가 [TeddSection]인 하나의 [LazyColumn]이다. masthead, 0개 이상의 상태
 * block(오류 banner, 빈 상태), form block(정렬/필터), 세 문서 collection을 담는다. 모든 block을
 * [TeddSection]으로 통과시키면서 익명 header `Column`과 간격 및 가로 inset이 서로 달랐던 세 가지 형태의
 * 섹션 composable을 대체했다. 이것이 중요했던 이유는 [TeddSection]을 참고한다.
 *
 * 라이브러리 그리드 다중 선택은 뷰 모델이 아니라 이 composable 내부에서 전적으로 관리한다.
 * [selectedDocumentIds]는 이 화면을 떠나는 navigation 이후까지 유지되지 않으며, 시스템 뒤로 가기 gesture는
 * 앱의 다른 picker UI와 마찬가지로 다른 작업보다 먼저 선택을 지우도록 [NavigationBackHandler]를 통해
 * 연결한다.
 *
 * `LazyColumn`이 받는 위쪽 inset은 두 번째 독립적인 `WindowInsets` 읽기 없이 `TeddScaffold` 자체의
 * `scaffoldPadding`에서만 읽는다. 선택 top bar slot을 `AnimatedVisibility`로 감쌌고 Compose animation
 * runtime은 `AnimatedVisibility`가 완전히 숨겨지고 더는 animation 중이 아니면 layout node를 전혀
 * 발행하지 않으므로 이 방식이 동작한다. 선택이 끝나고 exit animation이 안정되면 해당 slot은 단순히
 * 높이가 0인 것이 아니라 실제로 비게 된다. Material의 `Scaffold`는 빈 bar slot을 "no bar"로 취급하고
 * 해당 edge에서 `contentWindowInsets`(`TeddScaffold`의 기본값인 `WindowInsets.safeDrawing`) 측정으로
 * 돌아간다. 따라서 이 composable이 `WindowInsets.statusBars`를 두 번째로 읽지 않아도 유휴 상태에서
 * `scaffoldPadding.calculateTopPadding()`은 이미 status bar 높이와 같다. 선택 중에는 같은 값이 자체 내부의
 * status bar 소비를 반영한 선택 top bar의 실제 측정 높이를 대신 보고한다. `scaffoldPadding`만으로 제공할
 * 수 없는 유일한 부분은 masthead의 status bar 아래 추가 여백이므로 유휴 상태에서는 [contentPadding]을
 * 여전히 더한다. 선택 top bar 아래에는 이런 margin이 필요 없으므로 선택 중에는 더하지 않는다.
 *
 * 측정 높이는 transition이 끝날 때 한 번에 바뀌지 않고 전체 transition 동안 부드럽게 변해야 한다. 그래서
 * 선택 top bar의 `enter`/`exit`는 `fadeIn`/`fadeOut`, `slideInVertically`/`slideOutVertically`와
 * `expandVertically`/`shrinkVertically`를 조합하며 각 phase의 `tween` duration을 공유한다. fade와 slide만
 * 사용하면 `AnimatedVisibility`가 부모에 보고하는 크기는 줄지 않는다. 크기 animation이 없으면 `Scaffold`의
 * `topBarPlaceables`가 fade/slide 내내 bar의 전체 높이를 유지하다가 node가 최종 제거되는 한 frame에만
 * 0으로 줄어든다. 여기에 선택 집합이 비거나 비지 않는 순간 [contentPadding]이 `resolvedTopPadding`에서
 * 켜지거나 꺼지는 동작까지 겹쳐, 과거에는 양방향 모두 스크롤 콘텐츠가 한 frame에 bar 전체 높이(약 56dp)만큼
 * 튀었다.
 *
 * @param uiState 문서, 섹션, 정렬/필터, 로딩과 오류 상태를 담은 화면 데이터.
 * @param onOpenFilesClick 사용자가 파일을 선택해 문서를 추가할 때 호출한다.
 * @param onOpenFolderClick 사용자가 폴더를 선택해 문서를 추가할 때 호출한다.
 * @param onOpenGoogleDriveClick 사용자가 Google Drive에서 문서를 추가할 때 호출한다. null이면 문서 추가
 *   dialog에서 해당 옵션을 숨긴다.
 * @param onSettingsClick 설정 동작을 누를 때 호출한다.
 * @param onDocumentClick 사용자가 열려고 누른 문서 id를 전달해 호출한다. 라이브러리 선택이 활성화되어
 *   있으면 대신 tap으로 선택 상태를 전환한다.
 * @param scrollState 화면 [LazyColumn]의 스크롤 상태. 다른 hoist된 스크롤 상태처럼 recomposition/navigation을
 *   견딜 수 있도록 호출자가 소유한다.
 * @param onOpenLibraryClick 사용자가 미리보기 너머의 전체 라이브러리를 보려고 할 때 호출한다.
 * @param onOpenLibraryFolderClick 사용자가 라이브러리 폴더를 열 때 폴더 id를 전달해 호출한다.
 * @param onDocumentBookmarkChange 설정할 즐겨찾기 상태와 문서 id를 전달해 호출한다.
 * @param onSelectionBookmarkChange 현재 선택한 문서 id와 모두에게 한 번에 설정할 즐겨찾기 상태를 전달해 호출한다.
 * @param onDeleteDocuments 삭제 확인 후 제거할 문서 id를 전달해 호출한다.
 * @param onSortChange 라이브러리 정렬 순서가 바뀔 때 호출한다.
 * @param onFormatFilterChange 라이브러리 형식 필터가 바뀔 때 호출한다.
 * @param modifier 화면 root에 적용할 modifier.
 * @param contentPadding masthead 위와 화면 마지막 섹션 아래에 놓는 추가 세로 간격. [TeddScaffold]가
 *   `scaffoldPadding`으로 이미 제공하는 safe area/top bar inset 위에 더한다. null이면 위아래 모두 테마의
 *   screenPadding을 사용한다. 여기서는 세로 성분만 읽는다. 각 섹션 콘텐츠의 가로 inset은 이 매개변수가
 *   아니라 [TeddSection]이 소유한다.
 */
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onOpenFilesClick: () -> Unit,
    onOpenFolderClick: () -> Unit,
    onOpenGoogleDriveClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onDocumentClick: (DocumentId) -> Unit,
    scrollState: LazyListState,
    onOpenLibraryClick: () -> Unit = {},
    onOpenLibraryFolderClick: (String) -> Unit = {},
    onDocumentBookmarkChange: (DocumentId, Boolean) -> Unit = { _, _ -> },
    onSelectionBookmarkChange: (Collection<DocumentId>, Boolean) -> Unit = { _, _ -> },
    onDeleteDocuments: (Collection<DocumentId>) -> Unit = {},
    onSortChange: (HomeSort) -> Unit = {},
    onFormatFilterChange: (HomeFormatFilter) -> Unit = {},
    onLoadCover: (DocumentId) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(vertical = spacing.screenPadding)
    val motion = teddReaderMotion()
    val displayFold = rememberDisplayFold()
    var actionDocumentTarget by remember { mutableStateOf<HomeDocumentActionTarget?>(null) }
    var pendingDeleteDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedDocumentIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var previewMode by rememberSaveable { mutableStateOf(LibraryCollectionMode.All) }
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)

    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectedDocumentIds.isNotEmpty(),
        onBackCompleted = {
            selectedDocumentIds = emptySet()
        },
    )

    LaunchedEffect(uiState.libraryDocuments) {
        val libraryDocumentIds = uiState.libraryDocuments.mapTo(hashSetOf()) { it.id.value }
        selectedDocumentIds = selectedDocumentIds.filterTo(linkedSetOf()) { it in libraryDocumentIds }
        pendingDeleteDocumentIds = pendingDeleteDocumentIds.filterTo(linkedSetOf()) { it in libraryDocumentIds }
        if (actionDocumentTarget?.documentId !in libraryDocumentIds) {
            actionDocumentTarget = null
        }
    }

    if (uiState.isLoading) {
        TeddFullScreenLoadingIndicator(
            modifier = modifier,
            message = stringResource(Res.string.home_loading_recent_documents),
        )
        return
    }

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AnimatedVisibility(
                visible = selectedDocumentIds.isNotEmpty(),
                enter = fadeIn(tween(motion.mediumDurationMs)) +
                    slideInVertically(tween(motion.mediumDurationMs)) { -it } +
                    expandVertically(tween(motion.mediumDurationMs)),
                exit = fadeOut(tween(motion.shortDurationMs)) +
                    slideOutVertically(tween(motion.shortDurationMs)) { -it } +
                    shrinkVertically(tween(motion.shortDurationMs)),
            ) {
                val selectedDocuments = uiState.libraryDocuments.filter { it.id.value in selectedDocumentIds }
                val bookmarkTarget = homeSelectionBookmarkTarget(selectedDocuments)
                SelectionTopBar(
                    selectedCount = selectedDocumentIds.size,
                    bookmarkTarget = bookmarkTarget,
                    onCancelClick = {
                        selectedDocumentIds = emptySet()
                    },
                    onBookmarkClick = {
                        val documentIds = selectedDocumentIds.map(::DocumentId)
                        actionDocumentTarget = null
                        selectedDocumentIds = emptySet()
                        onSelectionBookmarkChange(documentIds, bookmarkTarget)
                    },
                    onDeleteClick = {
                        actionDocumentTarget = null
                        pendingDeleteDocumentIds = selectedDocumentIds
                    },
                )
            }
        },
    ) { scaffoldPadding ->
        val resolvedTopPadding = scaffoldPadding.calculateTopPadding() +
            if (selectedDocumentIds.isEmpty()) resolvedContentPadding.calculateTopPadding() else spacing.none
        val resolvedBottomPadding = scaffoldPadding.calculateBottomPadding() +
            resolvedContentPadding.calculateBottomPadding()

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val shortestDp = if (maxWidth <= maxHeight) maxWidth else maxHeight
            val previewLimit = libraryPreviewLimit(
                shortestSide = shortestDp,
                displayFold = displayFold,
                tabletMinWidth = breakpoints.medium,
            )
            val previewDocuments = remember(uiState.libraryDocuments, previewLimit) {
                homeLibraryPreviewDocuments(uiState.libraryDocuments, previewLimit)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = scrollState,
                contentPadding = PaddingValues(top = resolvedTopPadding, bottom = resolvedBottomPadding),
            ) {
                item {
                    TeddSection(kind = TeddSectionKind.Masthead) {
                        HomeMasthead(
                            showAddAction = uiState.hasDocuments,
                            onAddDocumentsClick = { showAddDialog = true },
                            onSettingsClick = onSettingsClick,
                        )
                    }
                }

                uiState.errorMessage?.let { message ->
                    item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = message)
                        }
                    }
                }

                uiState.unsupportedFormatMessage?.let { message ->
                    item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = message)
                        }
                    }
                }

                if (!uiState.hasDocuments) {
                    item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddEmptyState(
                                title = stringResource(Res.string.home_no_documents_title),
                                description = stringResource(Res.string.home_no_documents_description),
                                modifier = Modifier.fillMaxWidth(),
                                action = {
                                    TeddButton(
                                        text = stringResource(Res.string.add_documents),
                                        onClick = { showAddDialog = true },
                                    )
                                },
                            )
                        }
                    }
                } else {
                    item {
                        TeddSection(kind = TeddSectionKind.Form) {
                            HomeSortFilterControls(
                                sort = uiState.sort,
                                formatFilter = uiState.formatFilter,
                                onSortChange = onSortChange,
                                onFormatFilterChange = onFormatFilterChange,
                            )
                        }
                    }
                    if (uiState.libraryDocuments.isEmpty()) {
                        item {
                            TeddSection(kind = TeddSectionKind.Status) {
                                HomeFilteredEmptyState(
                                    onShowAllClick = { onFormatFilterChange(HomeFormatFilter.All) },
                                )
                            }
                        }
                    }
                }

                if (uiState.favoriteDocuments.isNotEmpty()) {
                    item {
                        HomeDocumentCollection(
                            section = HomeDocumentSection.Favorites,
                            title = stringResource(Res.string.favorites),
                            description = if (uiState.favoriteDocuments.size == 1) {
                                stringResource(Res.string.favorite_documents_single)
                            } else {
                                stringResource(Res.string.favorite_documents_count, uiState.favoriteDocuments.size)
                            },
                            documents = uiState.favoriteDocuments,
                            actionDocumentTarget = actionDocumentTarget,
                            showFavoriteIcon = true,
                            onDocumentClick = onDocumentClick,
                            onShowActions = {
                                actionDocumentTarget = HomeDocumentActionTarget(HomeDocumentSection.Favorites, it)
                            },
                            onDismissActions = { actionDocumentTarget = null },
                            onBookmarkClick = { document ->
                                actionDocumentTarget = null
                                onDocumentBookmarkChange(document.id, false)
                            },
                            onDeleteClick = { document ->
                                actionDocumentTarget = null
                                pendingDeleteDocumentIds = setOf(document.id.value)
                            },
                            documentCoverImages = uiState.documentCoverImages,
                            onLoadCover = onLoadCover,
                        )
                    }
                }

                if (uiState.recentDocuments.isNotEmpty()) {
                    item {
                        HomeDocumentCollection(
                            section = HomeDocumentSection.Recent,
                            title = stringResource(Res.string.recent_reading),
                            description = stringResource(Res.string.recent_reading_description),
                            documents = uiState.recentDocuments,
                            actionDocumentTarget = actionDocumentTarget,
                            onDocumentClick = onDocumentClick,
                            onShowActions = {
                                actionDocumentTarget = HomeDocumentActionTarget(HomeDocumentSection.Recent, it)
                            },
                            onDismissActions = { actionDocumentTarget = null },
                            onBookmarkClick = { document ->
                                actionDocumentTarget = null
                                onDocumentBookmarkChange(document.id, true)
                            },
                            onDeleteClick = { document ->
                                actionDocumentTarget = null
                                pendingDeleteDocumentIds = setOf(document.id.value)
                            },
                            documentCoverImages = uiState.documentCoverImages,
                            onLoadCover = onLoadCover,
                        )
                    }
                }

                if (uiState.libraryDocuments.isNotEmpty()) {
                    item {
                        HomeLibraryPreviewSection(
                            previewMode = previewMode,
                            onPreviewModeChange = { previewMode = it },
                            previewDocuments = previewDocuments,
                            allDocuments = uiState.libraryDocuments,
                            folders = uiState.libraryFolders,
                            previewLimit = previewLimit,
                            selectedDocumentIds = selectedDocumentIds,
                            actionDocumentTarget = actionDocumentTarget,
                            documentCoverImages = uiState.documentCoverImages,
                            onDocumentClick = { documentId ->
                                if (selectedDocumentIds.isNotEmpty()) {
                                    actionDocumentTarget = null
                                    selectedDocumentIds = selectedDocumentIds.toggle(documentId.value)
                                } else {
                                    onDocumentClick(documentId)
                                }
                            },
                            onStartSelection = { documentId ->
                                actionDocumentTarget = null
                                selectedDocumentIds = selectedDocumentIds + documentId.value
                            },
                            onShowActions = {
                                actionDocumentTarget = HomeDocumentActionTarget(HomeDocumentSection.Library, it)
                            },
                            onDismissActions = { actionDocumentTarget = null },
                            onBookmarkClick = { document ->
                                actionDocumentTarget = null
                                onDocumentBookmarkChange(document.id, !document.isBookmarked)
                            },
                            onDeleteClick = { document ->
                                actionDocumentTarget = null
                                pendingDeleteDocumentIds = setOf(document.id.value)
                            },
                            onFolderClick = onOpenLibraryFolderClick,
                            onViewAllClick = onOpenLibraryClick,
                            onLoadCover = onLoadCover,
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        HomeAddDocumentsDialog(
            onDismissRequest = { showAddDialog = false },
            onSelectFilesClick = {
                showAddDialog = false
                onOpenFilesClick()
            },
            onSelectFolderClick = {
                showAddDialog = false
                onOpenFolderClick()
            },
            onSelectGoogleDriveClick = onOpenGoogleDriveClick?.let {
                {
                    showAddDialog = false
                    it()
                }
            },
        )
    }

    val pendingDeleteDocuments = remember(uiState.libraryDocuments, pendingDeleteDocumentIds) {
        uiState.libraryDocuments.filter { it.id.value in pendingDeleteDocumentIds }
    }
    if (pendingDeleteDocuments.isNotEmpty()) {
        TeddAlertDialog(
            onDismissRequest = { pendingDeleteDocumentIds = emptySet() },
            title = stringResource(Res.string.remove_from_library_title),
            text = {
                TeddText(
                    text = if (pendingDeleteDocuments.size == 1) {
                        stringResource(
                            Res.string.remove_from_library_message,
                            pendingDeleteDocuments.single().location.displayName,
                        )
                    } else {
                        stringResource(
                            Res.string.remove_from_library_multiple_message,
                            pendingDeleteDocuments.size,
                        )
                    },
                )
            },
            confirmButton = {
                TeddButton(
                    text = stringResource(Res.string.delete),
                    onClick = {
                        val documentIds = pendingDeleteDocuments.map(DocumentMetadata::id)
                        pendingDeleteDocumentIds = emptySet()
                        selectedDocumentIds = selectedDocumentIds - documentIds.map(DocumentId::value).toSet()
                        onDeleteDocuments(documentIds)
                    },
                    emphasis = TeddButtonEmphasis.Destructive,
                )
            },
            dismissButton = {
                TeddButton(
                    text = stringResource(Res.string.cancel),
                    onClick = { pendingDeleteDocumentIds = emptySet() },
                    emphasis = TeddButtonEmphasis.Secondary,
                )
            },
        )
    }
}

/**
 * [selectedDocuments]의 일괄 동작이 설정해야 할 즐겨찾기 상태다. 선택한 문서 중 하나라도 아직 즐겨찾기가
 * 아니면 즐겨찾기에 추가하고, 모든 선택 문서가 이미 즐겨찾기일 때만 제거한다. 이 규칙으로
 * [SelectionTopBar]의 button 하나가 두 동작을 따로 제공하지 않고 혼합 선택에 따라 "add" 또는 "remove"로
 * 동작한다.
 *
 * @param selectedDocuments 라이브러리 그리드에서 현재 선택한 문서.
 * @return [selectedDocuments] 중 즐겨찾기 아닌 문서가 하나라도 있으면 true(즐겨찾기 추가), 모두 이미
 *   즐겨찾기이면 false(즐겨찾기 제거).
 */
internal fun homeSelectionBookmarkTarget(selectedDocuments: Collection<DocumentMetadata>): Boolean =
    selectedDocuments.any { !it.isBookmarked }

/**
 * 라이브러리 다중 선택 중 masthead 대신 표시하는 top bar다. 선택 개수, 일괄 즐겨찾기 전환과 일괄 삭제
 * 동작을 제공한다.
 *
 * @param selectedCount 현재 선택한 문서 수. 제목에 표시한다.
 * @param bookmarkTarget 일괄 동작 button이 적용할 즐겨찾기 상태. [homeSelectionBookmarkTarget] 참고.
 *   button에 표시할 icon(filled/outline)도 선택한다.
 * @param onCancelClick 사용자가 동작 없이 선택 모드를 나갈 때 호출한다.
 * @param onBookmarkClick 일괄 즐겨찾기 전환 동작을 누를 때 호출한다.
 * @param onDeleteClick 일괄 삭제 동작을 누를 때 호출한다.
 * @param modifier bar root에 적용할 modifier.
 */
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    bookmarkTarget: Boolean,
    onCancelClick: () -> Unit,
    onBookmarkClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = teddReaderColors()
    val bookmarkDescription = if (bookmarkTarget) {
        stringResource(Res.string.add_to_favorites)
    } else {
        stringResource(Res.string.remove_from_favorites)
    }
    TeddTopBar(
        title = stringResource(Res.string.home_selection_count, selectedCount),
        modifier = modifier,
        navigationIcon = {
            TeddIconButton(
                onClick = onCancelClick,
                contentDescription = stringResource(Res.string.cancel),
            ) {
                TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
            }
        },
        actions = {
            TeddIconButton(
                onClick = onBookmarkClick,
                contentDescription = bookmarkDescription,
            ) {
                TeddIcon(
                    imageVector = if (bookmarkTarget) TeddIcons.BookmarkFilled else TeddIcons.BookmarkOutline,
                    contentDescription = null,
                    tint = colors.primary,
                )
            }
            TeddIconButton(
                onClick = onDeleteClick,
                contentDescription = stringResource(Res.string.delete),
            ) {
                TeddIcon(
                    imageVector = TeddIcons.Delete,
                    contentDescription = null,
                    tint = colors.error,
                )
            }
        },
    )
}

/**
 * 홈 화면의 라이브러리 섹션이다. All/Folders 전환과 개별 문서 또는 폴더 표지의 제한된 그리드 미리보기를
 * 제공하며, 전체 라이브러리로 이동하는 "show all" 동작을 포함한다.
 *
 * `fullBleed = false`인 [TeddSectionKind.Collection]으로 렌더링한다. 미리보기는 가로 스크롤 선반이 아니라
 * 열 수가 고정된 그리드이므로 [HomeDocumentCollection]의 pager 기반 선반처럼 화면의 가로 inset을 무시할
 * 이유가 없다.
 *
 * [homeLibraryGridRows]는 마지막 행이 열 수보다 적은 항목으로 끝나면 남는 자리를 `null`로 채운다.
 * 이 함수는 그 `null` 자리마다 `weight(1f)`를 준 [Spacer]를 그린다. `Row`의 weight는 실제로 존재하는
 * 자식에게만 남은 너비를 나누므로, 짧은 마지막 행을 그대로 두면 남은 항목이 위쪽 완전한 행보다 넓게
 * 늘어나 열 경계가 어긋난다. 이 빈 자리는 실제 콘텐츠로 대체할 수 없는 순수한 레이아웃 자리표시자이므로
 * weighted [Spacer]가 유일한 방법이다.
 *
 * @param previewMode 그리드가 현재 모든 문서와 폴더 중 무엇을 표시하는지 나타낸다.
 * @param onPreviewModeChange All/Folders chip 선택이 바뀔 때 호출한다.
 * @param previewDocuments All 모드에 표시할 이미 제한된 문서.
 * @param allDocuments Folders 모드에서 각 폴더의 미리보기 썸네일을 계산할 전체 라이브러리 문서 목록.
 * @param folders 표시 전에 [previewLimit]로 한 번 더 제한할 라이브러리 폴더.
 * @param previewLimit 가용 화면 크기에 따라 선택한 미리보기 최대 타일 수(`libraryPreviewLimit` 참고).
 *   2열 또는 4열 그리드도 선택한다.
 * @param selectedDocumentIds 일괄 동작을 위해 현재 선택한 문서 id.
 * @param actionDocumentTarget 현재 더보기 메뉴가 열린 섹션/문서. 없으면 null.
 * @param documentCoverImages 문서 id를 키로 하는 미리 디코딩된 표지 바이트.
 * @param onDocumentClick tap한 문서 id를 전달해 호출한다.
 * @param onStartSelection 길게 눌러 다중 선택을 시작할 문서 id를 전달해 호출한다.
 * @param onShowActions 더보기 메뉴를 열 문서 id를 전달해 호출한다.
 * @param onDismissActions 열려 있는 더보기 메뉴를 닫을 때 호출한다.
 * @param onBookmarkClick 더보기 메뉴에서 즐겨찾기를 전환할 문서를 전달해 호출한다.
 * @param onDeleteClick 더보기 메뉴에서 삭제를 요청할 문서를 전달해 호출한다.
 * @param onFolderClick 폴더 타일을 누를 때 폴더 id를 전달해 호출한다.
 * @param onViewAllClick "show all" 동작을 누를 때 호출한다.
 * @param onLoadCover 디코딩하고 캐시할 표지의 문서 id를 전달해 호출한다.
 * @param modifier 섹션 root에 적용할 modifier.
 */
@Composable
private fun HomeLibraryPreviewSection(
    previewMode: LibraryCollectionMode,
    onPreviewModeChange: (LibraryCollectionMode) -> Unit,
    previewDocuments: ImmutableList<DocumentMetadata>,
    allDocuments: ImmutableList<DocumentMetadata>,
    folders: ImmutableList<LibraryFolder>,
    previewLimit: Int,
    selectedDocumentIds: Set<String>,
    actionDocumentTarget: HomeDocumentActionTarget?,
    documentCoverImages: ImmutableMap<String, ByteArray>,
    onDocumentClick: (DocumentId) -> Unit,
    onStartSelection: (DocumentId) -> Unit,
    onShowActions: (String) -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: (DocumentMetadata) -> Unit,
    onDeleteClick: (DocumentMetadata) -> Unit,
    onFolderClick: (String) -> Unit,
    onViewAllClick: () -> Unit,
    onLoadCover: (DocumentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val previewFolders = remember(folders, previewLimit) { folders.take(previewLimit) }

    TeddSection(
        kind = TeddSectionKind.Collection,
        modifier = modifier,
        title = stringResource(Res.string.library),
        description = stringResource(Res.string.library_preview_description),
        action = {
            TeddButton(
                text = stringResource(Res.string.show_all),
                onClick = onViewAllClick,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            TeddChip(
                text = stringResource(Res.string.all),
                selected = previewMode == LibraryCollectionMode.All,
                onClick = { onPreviewModeChange(LibraryCollectionMode.All) },
            )
            TeddChip(
                text = stringResource(Res.string.folders),
                selected = previewMode == LibraryCollectionMode.Folders,
                onClick = { onPreviewModeChange(LibraryCollectionMode.Folders) },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            val columns = if (previewLimit > 4) 4 else 2

            when (previewMode) {
                LibraryCollectionMode.All -> homeLibraryGridRows(previewDocuments, columns).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    ) {
                        row.forEach { document ->
                            if (document == null) {
                                Spacer(modifier = Modifier.weight(1f))
                            } else {
                                DocumentCard(
                                    document = document,
                                    coverImageBytes = documentCoverImages[document.id.value],
                                    selected = document.id.value in selectedDocumentIds,
                                    actionsExpanded = actionDocumentTarget == HomeDocumentActionTarget(
                                        HomeDocumentSection.Library,
                                        document.id.value,
                                    ),
                                    onClick = { onDocumentClick(document.id) },
                                    singleClick = selectedDocumentIds.isEmpty(),
                                    onLongClick = { onStartSelection(document.id) },
                                    onShowActions = { onShowActions(document.id.value) },
                                    onDismissActions = onDismissActions,
                                    onBookmarkClick = { onBookmarkClick(document) },
                                    onDeleteClick = { onDeleteClick(document) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(3f / 4f),
                                    onLoadCover = { onLoadCover(document.id) },
                                )
                            }
                        }
                    }
                }

                LibraryCollectionMode.Folders -> if (previewFolders.isEmpty()) {
                    TeddEmptyState(
                        title = stringResource(Res.string.library_empty_folder_title),
                        description = stringResource(Res.string.library_empty_folder_description),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    homeLibraryGridRows(previewFolders, columns).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(spacing.small),
                        ) {
                            row.forEach { folder ->
                                if (folder == null) {
                                    Spacer(modifier = Modifier.weight(1f))
                                } else {
                                    val folderPreviewDocuments = libraryFolderPreviewDocuments(
                                        documents = allDocuments,
                                        folderId = folder.id,
                                        previewLimit = previewLimit,
                                    )
                                    FolderCoverCard(
                                        folder = folder,
                                        previewDocuments = folderPreviewDocuments,
                                        remainingDocumentCount = libraryFolderRemainingDocumentCount(
                                            totalCount = folder.documentCount,
                                            previewCount = folderPreviewDocuments.size,
                                        ),
                                        documentCoverImages = documentCoverImages,
                                        onClick = { onFolderClick(folder.id) },
                                        singleClick = true,
                                        modifier = Modifier.weight(1f),
                                        onLoadCover = { previewDocument -> onLoadCover(previewDocument.id) },
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
 * 제목이 있고 가로 페이지 방식으로 넘기는 문서 선반이다. 즐겨찾기와 최근 읽기 collection이 이 layout을
 * 공유하며 제목/설명/icon과 담은 문서만 다르다.
 *
 * `fullBleed = true`인 [TeddSectionKind.Collection]으로 렌더링한다. 위쪽 heading은 화면의 다른 모든
 * 섹션과 맞추면서 pager 카드는 화면 edge까지 스크롤할 수 있다. 두 부분에 서로 다른 inset이 필요한
 * 이유는 [TeddSection]을 참고한다. [showFavoriteIcon]은 이전에 제목 앞에 있던 bookmark glyph를
 * [TeddSection]의 뒤쪽 `action` slot으로 옮긴다. 일반 heading에는 재사용할 leading icon slot이 없기 때문이다.
 *
 * @param section 이 선반의 종류(즐겨찾기 또는 최근). [HomeDocumentPager]로 전달하며 문서 id와 조합해
 *   어느 카드의 더보기 메뉴가 열렸는지 식별한다.
 * @param title 섹션 제목.
 * @param description 섹션 보조 설명 text.
 * @param documents 이 선반에 표시할 문서.
 * @param actionDocumentTarget 현재 더보기 메뉴가 열린 섹션/문서. 없으면 null.
 * @param onDocumentClick tap한 문서 id를 전달해 호출한다.
 * @param onShowActions 더보기 메뉴를 열 문서 id를 전달해 호출한다.
 * @param onDismissActions 열려 있는 더보기 메뉴를 닫을 때 호출한다.
 * @param onBookmarkClick 더보기 메뉴에서 즐겨찾기를 전환할 문서를 전달해 호출한다.
 * @param onDeleteClick 더보기 메뉴에서 삭제를 요청할 문서를 전달해 호출한다.
 * @param modifier 섹션 root에 적용할 modifier.
 * @param documentCoverImages 문서 id를 키로 하는 미리 디코딩된 표지 바이트.
 * @param onLoadCover 디코딩하고 캐시할 표지의 문서 id를 전달해 호출한다.
 * @param showFavoriteIcon 섹션 heading의 뒤쪽 slot에 bookmark icon을 표시할지 여부. 즐겨찾기 collection을
 *   특별히 표시할 때 사용한다.
 */
@Composable
private fun HomeDocumentCollection(
    section: HomeDocumentSection,
    title: String,
    description: String,
    documents: ImmutableList<DocumentMetadata>,
    actionDocumentTarget: HomeDocumentActionTarget?,
    onDocumentClick: (DocumentId) -> Unit,
    onShowActions: (String) -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: (DocumentMetadata) -> Unit,
    onDeleteClick: (DocumentMetadata) -> Unit,
    modifier: Modifier = Modifier,
    documentCoverImages: ImmutableMap<String, ByteArray> = persistentMapOf(),
    onLoadCover: (DocumentId) -> Unit,
    showFavoriteIcon: Boolean = false,
) {
    val colors = teddReaderColors()

    TeddSection(
        kind = TeddSectionKind.Collection,
        modifier = modifier,
        title = title,
        description = description,
        action = if (showFavoriteIcon) {
            {
                TeddIcon(
                    imageVector = TeddIcons.BookmarkFilled,
                    contentDescription = null,
                    tint = colors.primary,
                )
            }
        } else {
            null
        },
        fullBleed = true,
    ) {
        HomeDocumentPager(
            section = section,
            documents = documents,
            actionDocumentTarget = actionDocumentTarget,
            onDocumentClick = onDocumentClick,
            onShowActions = onShowActions,
            onDismissActions = onDismissActions,
            onBookmarkClick = onBookmarkClick,
            onDeleteClick = onDeleteClick,
            documentCoverImages = documentCoverImages,
            onLoadCover = onLoadCover,
        )
    }
}

/**
 * [HomeDocumentCollection]에서 선반의 문서 목록을 렌더링하는 고정 너비 [DocumentCard]의
 * [HorizontalPager]다.
 *
 * 여기의 카드는 선택할 수 없다. [DocumentCard.selected]는 false로 고정하고 길게 눌러도 선택을 시작하지
 * 않는다. 즐겨찾기와 최근 읽기는 책으로 들어가는 바로 가기이지 라이브러리 관리 장소가 아니다. 여기에서
 * 선택하면 이 선반과 라이브러리 그리드에 모두 있는 책이 중복되며, 선택이 제공하는 일괄 동작은 모두
 * 라이브러리 섹션에 속한다.
 *
 * @param section 이 선반의 종류. 문서 id와 조합해 열린 더보기 메뉴를 식별한다.
 * @param documents 페이지마다 하나씩 표시할 문서.
 * @param actionDocumentTarget 현재 더보기 메뉴가 열린 섹션/문서. 없으면 null.
 * @param onDocumentClick tap한 문서 id를 전달해 호출한다.
 * @param onShowActions 더보기 메뉴를 열 문서 id를 전달해 호출한다.
 * @param onDismissActions 열려 있는 더보기 메뉴를 닫을 때 호출한다.
 * @param onBookmarkClick 더보기 메뉴에서 즐겨찾기를 전환할 문서를 전달해 호출한다.
 * @param onDeleteClick 더보기 메뉴에서 삭제를 요청할 문서를 전달해 호출한다.
 * @param documentCoverImages 문서 id를 키로 하는 미리 디코딩된 표지 바이트.
 * @param onLoadCover 디코딩하고 캐시할 표지의 문서 id를 전달해 호출한다.
 * @param modifier pager root에 적용할 modifier.
 */
@Composable
private fun HomeDocumentPager(
    section: HomeDocumentSection,
    documents: ImmutableList<DocumentMetadata>,
    actionDocumentTarget: HomeDocumentActionTarget?,
    onDocumentClick: (DocumentId) -> Unit,
    onShowActions: (String) -> Unit,
    onDismissActions: () -> Unit,
    onBookmarkClick: (DocumentMetadata) -> Unit,
    onDeleteClick: (DocumentMetadata) -> Unit,
    documentCoverImages: ImmutableMap<String, ByteArray>,
    onLoadCover: (DocumentId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val pagerState = rememberPagerState(pageCount = { documents.size })

    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .fillMaxWidth()
            .height(HomeDocumentCardWidth * 4f / 3f),
        pageSize = PageSize.Fixed(HomeDocumentCardWidth),
        pageSpacing = spacing.medium,
        userScrollEnabled = documents.size > 1,
        contentPadding = PaddingValues(horizontal = spacing.screenPadding),
    ) { page ->
        val document = documents[page]
        DocumentCard(
            document = document,
            coverImageBytes = documentCoverImages[document.id.value],
            selected = false,
            actionsExpanded = actionDocumentTarget == HomeDocumentActionTarget(section, document.id.value),
            onClick = { onDocumentClick(document.id) },
            singleClick = true,
            onShowActions = { onShowActions(document.id.value) },
            onDismissActions = onDismissActions,
            onBookmarkClick = { onBookmarkClick(document) },
            onDeleteClick = { onDeleteClick(document) },
            modifier = Modifier.fillMaxWidth(),
            onLoadCover = { onLoadCover(document.id) },
        )
    }
}

/** [HomeDocumentPager] 선반 안 문서 카드의 고정 너비. */
private val HomeDocumentCardWidth = 180.dp

/**
 * 홈 화면의 header block이다. "Library" 레이블, 앱 이름, tagline, 문서 추가와 설정 동작을 담는다.
 *
 * @param showAddAction 문서 추가 button 표시 여부. 라이브러리에 이미 문서가 있으면 빈 상태 자체의 추가
 *   동작과 중복되지 않도록 숨긴다.
 * @param onAddDocumentsClick 문서 추가 동작을 누를 때 호출한다.
 * @param onSettingsClick 설정 동작을 누를 때 호출한다.
 * @param modifier masthead root에 적용할 modifier.
 */
@Composable
private fun HomeMasthead(
    showAddAction: Boolean,
    onAddDocumentsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        TeddText(
            text = stringResource(Res.string.library),
            style = typography.documentMeta,
            color = colors.onSurfaceVariant,
        )
        TeddText(
            text = "TeddReader",
            style = typography.headlineSmall,
        )
        TeddText(
            text = stringResource(Res.string.masthead_description),
            style = typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            if (showAddAction) {
                TeddButton(
                    text = stringResource(Res.string.add_documents),
                    onClick = onAddDocumentsClick,
                )
            }
            TeddButton(
                text = stringResource(Res.string.settings),
                onClick = onSettingsClick,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        }
    }
}

/**
 * 문서 추가 동작이 제공하는 dialog다. 로컬 파일/폴더 선택과 플랫폼이 지원할 때 Google Drive를 제공한다.
 *
 * @param onDismissRequest 동작 없이 dialog를 닫아야 할 때 호출한다.
 * @param onSelectFilesClick "select files" 행을 누를 때 호출한다.
 * @param onSelectFolderClick "select folder" 행을 누를 때 호출한다.
 * @param onSelectGoogleDriveClick Google Drive 행을 누를 때 호출한다. null이면 해당 행을 완전히 숨긴다.
 */
@Composable
private fun HomeAddDocumentsDialog(
    onDismissRequest: () -> Unit,
    onSelectFilesClick: () -> Unit,
    onSelectFolderClick: () -> Unit,
    onSelectGoogleDriveClick: (() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val trailingIcon: @Composable RowScope.() -> Unit = {
        TeddIcon(
            imageVector = TeddIcons.Next,
            contentDescription = null,
            size = teddReaderIconography().small,
            tint = colors.onSurfaceVariant,
        )
    }

    TeddAlertDialog(
        onDismissRequest = onDismissRequest,
        title = stringResource(Res.string.add_documents),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                TeddText(text = stringResource(Res.string.home_add_documents_description))

                Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                    TeddText(
                        text = stringResource(Res.string.local_documents),
                        style = typography.titleSmall,
                        color = colors.onSurfaceVariant,
                    )
                    TeddCard(modifier = Modifier.fillMaxWidth()) {
                        TeddListItem(
                            title = stringResource(Res.string.select_files),
                            supportingText = stringResource(Res.string.select_files_description),
                            onClick = onSelectFilesClick,
                            trailingContent = trailingIcon,
                        )
                        TeddListItem(
                            title = stringResource(Res.string.select_folder),
                            supportingText = stringResource(Res.string.select_folder_description),
                            onClick = onSelectFolderClick,
                            showDivider = false,
                            trailingContent = trailingIcon,
                        )
                    }
                }

                onSelectGoogleDriveClick?.let { onClick ->
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
                        TeddText(
                            text = stringResource(Res.string.cloud_documents),
                            style = typography.titleSmall,
                            color = colors.onSurfaceVariant,
                        )
                        TeddCard(modifier = Modifier.fillMaxWidth()) {
                            TeddListItem(
                                title = stringResource(Res.string.google_drive),
                                supportingText = stringResource(Res.string.google_drive_description),
                                onClick = onClick,
                                showDivider = false,
                                trailingContent = trailingIcon,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

/**
 * 라이브러리 형식 필터에 일치하는 문서가 없을 때 표시하는 빈 상태다. 전체 라이브러리의 빈 상태와 구분하여
 * 여기의 복구 동작은 가져오기 대신 필터를 해제한다.
 *
 * @param onShowAllClick 사용자가 활성 필터를 해제하려고 할 때 호출한다.
 * @param modifier 빈 상태 root에 적용할 modifier.
 */
@Composable
private fun HomeFilteredEmptyState(
    onShowAllClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        TeddText(
            text = stringResource(Res.string.home_no_matching_documents),
            style = typography.titleMedium,
        )
        TeddText(
            text = stringResource(Res.string.home_no_matching_documents_description),
            style = typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        TeddButton(
            text = stringResource(Res.string.show_all),
            onClick = onShowAllClick,
            emphasis = TeddButtonEmphasis.Secondary,
        )
    }
}

/**
 * 라이브러리 미리보기 그리드 위에 표시하는 정렬 순서와 형식 필터 chip 행이다.
 *
 * @param sort 현재 선택한 정렬 순서.
 * @param formatFilter 현재 선택한 형식 필터.
 * @param onSortChange 정렬 chip을 누를 때 호출한다.
 * @param onFormatFilterChange 형식 필터 chip을 누를 때 호출한다.
 * @param modifier control root에 적용할 modifier.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSortFilterControls(
    sort: HomeSort,
    formatFilter: HomeFormatFilter,
    onSortChange: (HomeSort) -> Unit,
    onFormatFilterChange: (HomeFormatFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        HomeChipGroup(
            label = stringResource(Res.string.sort),
            style = typography.documentMeta,
            contentColor = colors.onSurfaceVariant,
        ) {
            HomeSort.entries.forEach { option ->
                TeddChip(
                    text = option.chipLabel(),
                    selected = sort == option,
                    onClick = { onSortChange(option) },
                )
            }
        }
        HomeChipGroup(
            label = stringResource(Res.string.format),
            style = typography.documentMeta,
            contentColor = colors.onSurfaceVariant,
        ) {
            HomeFormatFilter.entries.forEach { option ->
                TeddChip(
                    text = option.chipLabel(),
                    selected = formatFilter == option,
                    onClick = { onFormatFilterChange(option) },
                )
            }
        }
    }
}

/**
 * 레이블이 있고 줄바꿈되는 chip 행이다. [HomeSortFilterControls]의 정렬 행과 형식 필터 행이 공유하는
 * layout이다.
 *
 * @param label chip 위에 표시할 행 caption.
 * @param style [label]에 적용할 text style.
 * @param contentColor [label]에 적용할 색상.
 * @param content chip 자체.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeChipGroup(
    label: String,
    style: TextStyle,
    contentColor: Color,
    content: @Composable () -> Unit,
) {
    val spacing = teddReaderSpacing()

    Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
        TeddText(
            text = label,
            style = style,
            color = contentColor,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            content()
        }
    }
}

/**
 * 정렬 옵션의 현지화된 chip 레이블이다.
 *
 * @receiver 레이블을 만들 정렬 옵션.
 * @return chip 표시 text.
 */
@Composable
private fun HomeSort.chipLabel(): String = when (this) {
    HomeSort.Recent -> stringResource(Res.string.recent)
    HomeSort.Title -> stringResource(Res.string.title)
    HomeSort.Format -> stringResource(Res.string.format)
}

/**
 * 형식 필터의 chip 레이블이다. "All" 옵션은 현지화하고 나머지는 앱의 다른 곳에서도 번역하지 않는 형식
 * 자체의 짧은 코드(예: "PDF")로 표시한다.
 *
 * @receiver 레이블을 만들 형식 필터.
 * @return chip 표시 text.
 */
@Composable
private fun HomeFormatFilter.chipLabel(): String = when (this) {
    HomeFormatFilter.All -> stringResource(Res.string.all)
    HomeFormatFilter.Txt -> "TXT"
    HomeFormatFilter.Pdf -> "PDF"
    HomeFormatFilter.Epub -> "EPUB"
    HomeFormatFilter.Comic -> "CBZ"
    HomeFormatFilter.Image -> "IMAGE"
}

/**
 * 이 집합에 [value]가 없으면 추가하고 있으면 제거한다. 라이브러리 다중 선택에서 문서 소속을 뒤집는 표준
 * toggle이다.
 *
 * @receiver 현재 집합.
 * @param value 소속을 전환할 원소.
 * @return [value]의 소속을 뒤집은 새 집합.
 */
private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * 알 수 있으면 문서의 페이지 수, 아니면 형식 이름이다. list item의 supporting text slot에 넣을 짧은 줄이다.
 *
 * @receiver 설명할 문서.
 * @return 형식화한 페이지 수. 페이지 수를 알 수 없으면 형식 이름.
 */
@Composable
private fun DocumentMetadata.supportingText(): String =
    pageCount?.let { stringResource(Res.string.document_pages, it) } ?: format.name

/** 아직 라이브러리에 문서가 없는 [HomeScreen]의 Compose preview. */
@Preview(widthDp = 240)
@Preview(widthDp = 280)
@Composable
private fun HomeScreenEmptyPreview() {
    TeddReaderTheme {
        HomeScreen(
            uiState = HomeUiState(
                isLoading = false,
                hasDocuments = false,
            ),
            onOpenFilesClick = {},
            onOpenFolderClick = {},
            onSettingsClick = {},
            onDocumentClick = {},
            onLoadCover = {},
            scrollState = rememberLazyListState(),
        )
    }
}

/**
 * 즐겨찾기, 최근 읽기와 라이브러리 폴더를 채운 [HomeScreen]의 Compose preview. compact/medium/expanded
 * 너비를 모두 표시한다.
 */
@Preview(widthDp = 240)
@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 840)
@Composable
private fun HomeScreenRecentPreview() {
    TeddReaderTheme {
        HomeScreen(
            uiState = HomeUiState(
                isLoading = false,
                hasDocuments = true,
                favoriteDocuments = persistentListOf(
                    DocumentMetadata(
                        id = DocumentId("preview-1"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.epub",
                            displayName = "미드나잇 에디토리얼 샘플 원고.epub",
                            sizeBytes = 2_400_000L,
                        ),
                        format = DocumentFormat.EPUB,
                        addedAtEpochMillis = 0L,
                        pageCount = 320,
                        isBookmarked = true,
                    ),
                ),
                recentDocuments = persistentListOf(
                    DocumentMetadata(
                        id = DocumentId("preview-2"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.pdf",
                            displayName = "A Very Long Korean File Name That Should Still Stay Calm On Narrow Screens.pdf",
                            sizeBytes = 420_000L,
                        ),
                        format = DocumentFormat.PDF,
                        addedAtEpochMillis = 0L,
                        pageCount = 48,
                    ),
                ),
                libraryDocuments = persistentListOf(
                    DocumentMetadata(
                        id = DocumentId("preview-2"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.pdf",
                            displayName = "A Very Long Korean File Name That Should Still Stay Calm On Narrow Screens.pdf",
                            sizeBytes = 420_000L,
                        ),
                        format = DocumentFormat.PDF,
                        addedAtEpochMillis = 0L,
                        pageCount = 48,
                    ),
                    DocumentMetadata(
                        id = DocumentId("preview-3"),
                        location = DocumentLocation(
                            sourceUri = "file:///preview.txt",
                            displayName = "Pocket essay.txt",
                            sizeBytes = 32_000L,
                        ),
                        format = DocumentFormat.TXT,
                        addedAtEpochMillis = 3_000L,
                        folderId = "folder-1",
                        folderName = "Weekend Reads",
                    ),
                ),
                libraryFolders = persistentListOf(LibraryFolder("folder-1", "Weekend Reads", 1)),
            ),
            onOpenFilesClick = {},
            onOpenFolderClick = {},
            onSettingsClick = {},
            onDocumentClick = {},
            onLoadCover = {},
            scrollState = rememberLazyListState(),
        )
    }
}
