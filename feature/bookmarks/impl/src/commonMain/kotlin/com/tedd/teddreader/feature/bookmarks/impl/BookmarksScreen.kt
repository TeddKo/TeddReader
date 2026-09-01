package com.tedd.teddreader.feature.bookmarks.impl

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.ui.component.TeddAlertDialog
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddErrorBanner
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddListItem
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddModalBottomSheet
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddSection
import com.tedd.teddreader.core.ui.component.TeddSectionKind
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.component.TeddTextField
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * 단일 문서의 저장 위치를 보여 주는 [BookmarksScreen]에 [BookmarksViewModel]을 연결하는 진입점이다.
 * 아래의 [BookmarksScreen]과 마찬가지로 이 컴포저블은 뷰 모델에 상태와 콜백만 전달한다.
 * [BookmarksUiState]를 수집하고 모든 사용자 작업을 뷰 모델 호출로 전달하며, 자체적으로 북마크 데이터를
 * 보관하지 않는다.
 *
 * `note`는 사용자가 입력하는 동안 편집 시트의 텍스트 필드 값을 보관하는 초안이며,
 * [onSaveNote]/[BookmarksViewModel.saveNote]가 호출될 때만 저장소에 기록된다. [onEditClick]이 시트를 열면
 * 북마크에 저장된 메모로 초기화하지만, 이후에는 [BookmarksUiState]와 동기화하지 않는다. 사용자가 편집 중인
 * 필드를 외부 상태 변경이 중간에 덮어써서는 안 되기 때문이다. `pendingDeleteBookmarkId`와
 * `pendingDeleteFromEditSheet`는 저장되는 대응 값이 전혀 없는 단순 UI 상태이다. 삭제 확인 창이 열린 북마크와
 * 그 확인 요청이 편집 시트에서 발생했는지(확인할 때 시트도 닫음), 목록에서 직접 발생했는지만 추적한다.
 *
 * @param documentId 이 화면에서 저장 위치를 보여 줄 문서이다.
 * @param onBack 사용자가 저장 위치 화면을 나가려고 할 때 호출된다.
 * @param onBookmarkClick 사용자가 이동하려고 저장 위치를 탭하면 해당 위치와 함께 호출된다.
 * @param modifier 생성되는 [BookmarksScreen]에 적용할 수정자이다.
 * @param viewModel 북마크 화면의 뷰 모델이며, 기본값은 Koin을 통해 확인한 인스턴스이다.
 */
@Composable
fun BookmarksRouteScreen(
    documentId: String,
    onBack: () -> Unit,
    onBookmarkClick: (ReaderLocation) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookmarksViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var note by rememberSaveable(documentId) { mutableStateOf("") }
    var pendingDeleteBookmarkId by rememberSaveable(documentId) { mutableStateOf<String?>(null) }
    var pendingDeleteFromEditSheet by rememberSaveable(documentId) { mutableStateOf(false) }

    LaunchedEffect(documentId) {
        viewModel.setDocument(documentId)
    }

    BookmarksScreen(
        uiState = uiState,
        onBack = onBack,
        onBookmarkClick = onBookmarkClick,
        note = note,
        onNoteChange = { note = it },
        onEditClick = { bookmark ->
            note = bookmark.note.orEmpty()
            viewModel.startEdit(bookmark)
        },
        pendingDeleteBookmarkId = pendingDeleteBookmarkId,
        onRequestDelete = { bookmark, fromEditSheet ->
            pendingDeleteBookmarkId = bookmark.id
            pendingDeleteFromEditSheet = fromEditSheet
        },
        onDismissDeleteConfirmation = {
            pendingDeleteBookmarkId = null
            pendingDeleteFromEditSheet = false
        },
        onConfirmDelete = { bookmark ->
            if (pendingDeleteFromEditSheet) viewModel.dismissEdit()
            viewModel.deleteBookmark(bookmark)
            pendingDeleteBookmarkId = null
            pendingDeleteFromEditSheet = false
        },
        onDismissEdit = viewModel::dismissEdit,
        onSaveNote = viewModel::saveNote,
        listState = listState,
        modifier = modifier,
    )
}

/**
 * 단일 문서의 저장 위치 화면이다. 저장 위치 목록, 현재 편집 중인 위치를 위한 편집 시트, 삭제 확인 대화상자가
 * 있는 스캐폴드로 구성된다. 이 컴포저블은 뷰 모델에 상태와 콜백만 전달한다. 렌더링하는 모든 값은 [uiState]나
 * 다른 매개변수에서 오고, 모든 사용자 작업은 콜백으로 다시 전달되며, 자체적으로 저장 위치 상태를 보관하지
 * 않는다.
 *
 * 빈 상태 설명과 북마크 목록은 각각 하나의 [TeddSectionKind.Status] 또는 [TeddSectionKind.Collection]
 * 섹션이며 구조상 동시에 표시되지 않는다. 빈 상태에는 별도의 행동 유도 요소를 두지 않는다. 상단 바의 뒤로 가기
 * 작업이
 * 빈 저장 위치 화면에서 독자에게 필요한 유일한 내비게이션을 이미 제공하기 때문이다.
 * [TeddReaderBreakpoints.compactControlWidth][com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints.compactControlWidth]
 * 미만에서는 컨테이너가 좁아 중앙 정렬이 답답해 보이므로 빈 상태 메시지를 중앙 정렬에서 시작점 정렬로 바꾼다.
 *
 * @param uiState 뷰 모델이 게시한 저장 위치 화면의 현재 상태이다.
 * @param onBack 사용자가 화면을 나가려고 할 때 호출된다.
 * @param onBookmarkClick 사용자가 저장 위치 행을 탭하면 해당 위치와 함께 호출된다.
 * @param note 편집 시트의 텍스트 필드에 표시되는 현재 메모 초안이다.
 * @param onNoteChange 사용자가 편집 시트의 메모 필드에 입력할 때 호출된다.
 * @param onEditClick 사용자가 편집하려고 저장 위치를 열면 해당 위치와 함께 호출된다.
 * @param pendingDeleteBookmarkId 삭제 확인을 기다리는 저장 위치의 ID이며, 확인 대화상자를 표시하지 않아야
 *   하면 null이다.
 * @param onRequestDelete 삭제를 확인할 저장 위치 및 요청이 목록의 행이 아니라 편집 시트의 삭제 버튼에서
 *   발생했는지 여부와 함께 호출된다. 호출자는 이 값으로 확인 시 편집 시트도 닫아야 하는지 판단한다.
 * @param onDismissDeleteConfirmation 삭제를 확인하지 않고 삭제 확인 대화상자를 닫을 때 호출된다.
 * @param onConfirmDelete 사용자가 확인한 뒤 실제로 삭제할 저장 위치와 함께 호출된다.
 * @param onDismissEdit 저장하지 않고 편집 시트를 닫을 때 호출된다.
 * @param onSaveNote 사용자가 편집 시트에서 저장할 때 반영할 메모 텍스트와 함께 호출된다.
 * @param listState 저장 위치 목록의 스크롤 상태이다.
 * @param modifier 스캐폴드에 적용할 수정자이다.
 * @param contentPadding 목록 콘텐츠 위아래에 적용할 세로 패딩이다. 가로 인셋은 각 [TeddSection]이 소유하므로
 *   가로 성분은 무시한다. null이면 양쪽 가장자리에 테마의 screenPadding을 사용한다.
 */
@Composable
fun BookmarksScreen(
    uiState: BookmarksUiState,
    onBack: () -> Unit,
    onBookmarkClick: (ReaderLocation) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onEditClick: (Bookmark) -> Unit,
    pendingDeleteBookmarkId: String?,
    onRequestDelete: (Bookmark, Boolean) -> Unit,
    onDismissDeleteConfirmation: () -> Unit,
    onConfirmDelete: (Bookmark) -> Unit,
    onDismissEdit: () -> Unit,
    onSaveNote: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.screenPadding)
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    val subtitle = if (uiState.bookmarks.isNotEmpty()) {
        if (uiState.bookmarks.size == 1) {
            stringResource(Res.string.saved_places_single)
        } else {
            stringResource(Res.string.saved_places_count, uiState.bookmarks.size)
        }
    } else {
        null
    }

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.saved_places),
                subtitle = subtitle,
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
                .padding(scaffoldPadding),
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
                uiState.errorMessage?.let { message ->
                    item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddErrorBanner(message = message)
                        }
                    }
                }
                when {
                    uiState.isLoading -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            TeddLoadingIndicator(message = stringResource(Res.string.loading_saved_places))
                        }
                    }
                    uiState.bookmarks.isEmpty() -> item {
                        TeddSection(kind = TeddSectionKind.Status) {
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val compact = maxWidth < breakpoints.compactControlWidth
                                val textAlign = if (compact) TextAlign.Start else TextAlign.Center
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                                    horizontalAlignment = if (compact) Alignment.Start else Alignment.CenterHorizontally,
                                ) {
                                    TeddText(text = stringResource(Res.string.saved_places_empty_title), style = typography.titleMedium, textAlign = textAlign)
                                    TeddText(
                                        text = stringResource(Res.string.saved_places_empty_description),
                                        style = typography.settingDescription,
                                        color = colors.onSurfaceVariant,
                                        textAlign = textAlign,
                                    )
                                }
                            }
                        }
                    }
                    else -> item {
                        TeddSection(kind = TeddSectionKind.Collection) {
                            uiState.bookmarks.forEach { bookmark ->
                                key(bookmark.id) {
                                    BookmarkRow(
                                        bookmark = bookmark,
                                        onBookmarkClick = { onBookmarkClick(bookmark.location) },
                                        onEditClick = { onEditClick(bookmark) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        uiState.editingBookmark?.let { bookmark ->
            TeddModalBottomSheet(
                title = stringResource(Res.string.edit_saved_place),
                onDismissRequest = onDismissEdit,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
                    TeddText(text = bookmark.displayTitle(), style = typography.titleMedium)
                    if (bookmark.hasCustomLabel()) {
                        TeddText(
                            text = bookmark.location.displayLabel(),
                            style = typography.settingDescription,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    TeddTextField(
                        value = note,
                        onValueChange = onNoteChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(Res.string.note),
                        minLines = 3,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TeddButton(
                            text = stringResource(Res.string.save),
                            onClick = { onSaveNote(note) },
                            enabled = note != bookmark.note.orEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                        TeddButton(
                            text = stringResource(Res.string.delete),
                            onClick = { onRequestDelete(bookmark, true) },
                            emphasis = TeddButtonEmphasis.Destructive,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        val pendingDeleteBookmark = pendingDeleteBookmarkId?.let { bookmarkId ->
            uiState.bookmarks.firstOrNull { it.id == bookmarkId } ?: uiState.editingBookmark?.takeIf { it.id == bookmarkId }
        }
        if (pendingDeleteBookmark != null) {
            TeddAlertDialog(
                onDismissRequest = onDismissDeleteConfirmation,
                title = stringResource(Res.string.delete_saved_place_title),
                text = { TeddText(text = stringResource(Res.string.delete_saved_place_message, pendingDeleteBookmark.displayTitle())) },
                confirmButton = {
                    TeddButton(
                        text = stringResource(Res.string.delete),
                        onClick = { onConfirmDelete(pendingDeleteBookmark) },
                        emphasis = TeddButtonEmphasis.Destructive,
                    )
                },
                dismissButton = {
                    TeddButton(
                        text = stringResource(Res.string.cancel),
                        onClick = onDismissDeleteConfirmation,
                        emphasis = TeddButtonEmphasis.Text,
                    )
                },
            )
        }
    }
}

/**
 * [BookmarksScreen] 목록에서 저장 위치 하나를 나타내는 행이다. 제목, 보조 텍스트
 * ([buildBookmarkSupportingText] 참고), 편집 버튼을 표시한다.
 *
 * @param bookmark 이 행에 표시할 저장 위치이다.
 * @param onBookmarkClick 행 자체를 탭할 때 호출된다.
 * @param onEditClick 행의 편집 버튼을 탭할 때 호출된다.
 * @param modifier 행에 적용할 수정자이다.
 */
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    onBookmarkClick: () -> Unit,
    onEditClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TeddListItem(
        title = bookmark.displayTitle(),
        supportingText = buildBookmarkSupportingText(bookmark),
        onClick = onBookmarkClick,
        singleClick = true,
        modifier = modifier,
        trailingContent = {
            TeddIconButton(onClick = onEditClick, contentDescription = stringResource(Res.string.edit_saved_place)) {
                TeddIcon(imageVector = TeddIcons.MoreVert, contentDescription = null)
            }
        },
    )
}

/** 이전 빌드가 [Bookmark.label]을 null로 두는 대신 직접 저장했을 수 있는 "Page 12" 같은 일반적인
 * 비지역화 레이블과 일치한다. [hasCustomLabel]은 일치하는 값을 레이블 없음으로 처리하므로 [displayTitle]은
 * 잘못된 언어의 오래된 문자열을 표시하는 대신 현재 지역화된 실시간 위치 레이블을 사용한다. */
private val legacyPageLabelPattern = Regex("""^Page \d+$""")

/**
 * 이 저장 위치에 레이블이 없거나 이전 방식으로 자동 생성된 레이블([legacyPageLabelPattern] 참고)이 아니라
 * 사용자 또는 이전 빌드가 실제로 작성한 레이블이 있는지 확인한다.
 *
 * @receiver 확인할 저장 위치이다.
 */
private fun Bookmark.hasCustomLabel(): Boolean {
    val currentLabel = label
    return currentLabel != null && !legacyPageLabelPattern.matches(currentLabel)
}

/**
 * 이 저장 위치에 표시할 제목이다. [hasCustomLabel]이 true이면 자체 레이블을 사용하고, 그렇지 않으면 현재
 * 지역화된 위치 설명([ReaderLocation.displayLabel] 참고)을 사용한다.
 *
 * @receiver 제목을 만들 저장 위치이다.
 */
@Composable
private fun Bookmark.displayTitle(): String = label?.takeIf { hasCustomLabel() } ?: location.displayLabel()

/**
 * 저장 위치 제목 아래에 표시할 보조 텍스트이다. 작성된 메모가 있으면 메모를 사용하고, 그렇지 않으면
 * [displayTitle]이 대체값으로 사용하는 것과 같은 위치 설명을 사용한다. 따라서 메모가 없는 행도 제목을
 * 반복하지 않고 가리키는 위치를 알려 준다.
 *
 * @param bookmark 보조 텍스트를 만들 저장 위치이다.
 * @return 비어 있지 않은 메모가 있으면 메모이고, 그렇지 않으면 위치의 표시 레이블이다.
 */
@Composable
private fun buildBookmarkSupportingText(bookmark: Bookmark): String = bookmark.note?.takeIf { it.isNotBlank() } ?: bookmark.location.displayLabel()

/**
 * 자체 제목이 없는 저장 위치에 표시할, 이 위치가 가리키는 곳의 지역화된 사용자 친화적 설명이다. 세 가지
 * [ReaderLocation] 변형에 하나의 일반 레이블을 쓰지 않고 각 형식에 맞게 설명한다. PDF에는 페이지 번호,
 * 일반 텍스트에는 원시 텍스트 오프셋, EPUB 스파인 위치에는 섹션과 오프셋 쌍을 사용한다.
 *
 * @receiver 설명할 위치이다.
 */
@Composable
private fun ReaderLocation.displayLabel(): String = when (this) {
    is ReaderLocation.PdfPage -> stringResource(Res.string.bookmark_location_pdf_page, pageIndex + 1)
    is ReaderLocation.TextOffset -> stringResource(Res.string.bookmark_location_text_position, offset + 1)
    is ReaderLocation.EpubOffset -> stringResource(Res.string.bookmark_location_epub_position, spineIndex + 1, offset + 1)
}

/**
 * 샘플 저장 위치 하나로 [BookmarksScreen]을 세 가지 너비에서 표시하여 화면 콘텐츠가 렌더링할 수 있는
 * 소형, 기본, 확장 레이아웃을 확인하는 미리 보기이다.
 */
@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Preview(widthDp = 840)
@Composable
private fun BookmarksScreenPreview() {
    TeddReaderTheme {
        BookmarksScreen(
            uiState = BookmarksUiState(
                isLoading = false,
                bookmarks = persistentListOf(
                    Bookmark(
                        id = "preview",
                        documentId = DocumentId("preview"),
                        location = ReaderLocation.TextOffset(42L),
                        label = "Page 1",
                        note = "Interesting paragraph",
                        createdAtEpochMillis = 0L,
                    ),
                ),
            ),
            onBack = {},
            onBookmarkClick = {},
            note = "",
            onNoteChange = {},
            onEditClick = {},
            pendingDeleteBookmarkId = null,
            onRequestDelete = { _, _ -> },
            onDismissDeleteConfirmation = {},
            onConfirmDelete = {},
            onDismissEdit = {},
            onSaveNote = {},
            listState = rememberLazyListState(),
        )
    }
}
