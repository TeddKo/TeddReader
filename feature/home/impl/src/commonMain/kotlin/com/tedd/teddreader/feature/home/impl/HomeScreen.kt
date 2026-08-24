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
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The stateful entry point for the home screen: wires [HomeViewModel]'s state and actions into the
 * stateless [HomeScreen], the way `ReaderRouteScreen` does for the reader.
 *
 * @param modifier The modifier applied to [HomeScreen]'s root.
 * @param importMessage An error message from an import that just completed elsewhere in the app
 *   (e.g. a file picker result), shown in place of the view model's own error until the next state
 *   update replaces it.
 * @param onOpenFilesClick Called when the user chooses to add documents by picking files.
 * @param onOpenFolderClick Called when the user chooses to add documents by picking a folder.
 * @param onOpenGoogleDriveClick Called when the user chooses to add documents from Google Drive;
 *   null hides that option entirely where the platform does not support it.
 * @param onSettingsClick Called when the settings action is tapped.
 * @param onDocumentClick Called with the id of a document the user tapped to open.
 * @param onOpenLibraryClick Called when the user asks to see the full library beyond the preview.
 * @param onOpenLibraryFolderClick Called with a folder id when the user opens a library folder.
 * @param viewModel The screen's view model, obtained through Koin by default.
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
 * The home screen's UI: masthead, favourites/recent-reading carousels, a library preview grid, and
 * the dialogs (add-documents, delete-confirmation) and multi-select top bar that overlay it.
 *
 * The screen's scrolling body is one [LazyColumn] whose every child is a [TeddSection] — a masthead,
 * zero or more status blocks (error banners, empty states), a form block (sort/filter), and the
 * three document collections. Routing every block through [TeddSection] is what replaced an
 * anonymous header `Column` plus three differently-shaped section composables that used to disagree
 * on gaps and horizontal inset; see [TeddSection] for why that mattered.
 *
 * Library-grid multi-select is owned entirely inside this composable rather than in the view
 * model — [selectedDocumentIds] never outlives navigation away from this screen, and the system
 * back gesture is wired (via [NavigationBackHandler]) to clear a selection before it does anything
 * else, the same way a picker UI elsewhere in the app would.
 *
 * The top inset the `LazyColumn` receives is read from `TeddScaffold`'s own `scaffoldPadding`
 * alone, never from a second, independent `WindowInsets` read. That works because the selection
 * top bar's slot is wrapped in `AnimatedVisibility`, and Compose's animation runtime stops emitting
 * any layout node for an `AnimatedVisibility` once it is fully hidden and no longer animating —
 * so once a selection ends and its exit animation settles, that slot becomes genuinely empty rather
 * than merely zero-height. Material's `Scaffold` treats an empty bar slot as "no bar," and falls
 * back to measuring `contentWindowInsets` (`TeddScaffold`'s default, `WindowInsets.safeDrawing`) for
 * that edge instead — which is why `scaffoldPadding.calculateTopPadding()` already equals the
 * status bar's height while idle, without this composable reading `WindowInsets.statusBars` a
 * second time. While a selection is active, the same value instead reports the selection top bar's
 * real measured height, which already accounts for its own internal status-bar consumption. The one
 * piece `scaffoldPadding` cannot supply on its own is the masthead's extra breathing room below the
 * status bar, which is why [contentPadding] is still added on top of it while idle — the selection
 * top bar needs no such margin beneath it, so that addition is skipped once a selection is active.
 *
 * That measured height has to change smoothly across the whole transition rather than snapping once
 * it finishes, which is why the selection top bar's `enter`/`exit` combine `fadeIn`/`fadeOut` and
 * `slideInVertically`/`slideOutVertically` with `expandVertically`/`shrinkVertically`, sharing each
 * phase's own `tween` duration. Fade and slide alone never shrink the size an `AnimatedVisibility`
 * reports to its parent, so without the size animation `Scaffold`'s `topBarPlaceables` would hold the
 * bar's full height for the entire fade/slide and only collapse to nothing in the single frame the
 * node is finally removed — which, combined with [contentPadding] switching on or off
 * `resolvedTopPadding` the instant the selection set becomes empty or non-empty, used to jump the
 * scrolling content by the bar's whole height (about 56dp) in one frame, in both directions.
 *
 * @param uiState The screen's data: documents, sections, sort/filter, loading and error state.
 * @param onOpenFilesClick Called when the user chooses to add documents by picking files.
 * @param onOpenFolderClick Called when the user chooses to add documents by picking a folder.
 * @param onOpenGoogleDriveClick Called when the user chooses to add documents from Google Drive;
 *   null hides that option in the add-documents dialog.
 * @param onSettingsClick Called when the settings action is tapped.
 * @param onDocumentClick Called with the id of a document the user tapped to open, unless a
 *   library selection is active, in which case a tap toggles selection instead.
 * @param scrollState The scroll state for the screen's [LazyColumn], owned by the caller so it can
 *   survive recomposition/navigation the same way any other hoisted scroll state does.
 * @param onOpenLibraryClick Called when the user asks to see the full library beyond the preview.
 * @param onOpenLibraryFolderClick Called with a folder id when the user opens a library folder.
 * @param onDocumentBookmarkChange Called with a document id and the bookmark state to set for it.
 * @param onSelectionBookmarkChange Called with the currently selected document ids and the
 *   bookmark state to set for all of them at once.
 * @param onDeleteDocuments Called with the document ids to remove once a delete is confirmed.
 * @param onSortChange Called when the library sort order changes.
 * @param onFormatFilterChange Called when the library format filter changes.
 * @param modifier The modifier applied to the screen's root.
 * @param contentPadding Extra vertical spacing placed above the masthead and below the screen's
 *   last section, on top of the safe-area/top-bar inset [TeddScaffold] already contributes through
 *   its `scaffoldPadding`; null resolves to the theme's screenPadding for both the top and the
 *   bottom. Only the vertical components are read here — horizontal inset around every section's
 *   content is owned by [TeddSection], not by this parameter.
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
 * The bookmark state a bulk action on [selectedDocuments] should set: favourite when any selected
 * document is not yet a favourite, unfavourite only once every selected document already is one.
 * This is what lets one button in [SelectionTopBar] read as "add" or "remove" depending on the
 * mixed selection, rather than requiring the two actions to be offered separately.
 *
 * @param selectedDocuments The documents currently selected in the library grid.
 * @return True (favourite) if any document in [selectedDocuments] is not bookmarked; false
 *   (unfavourite) only when all of them already are.
 */
internal fun homeSelectionBookmarkTarget(selectedDocuments: Collection<DocumentMetadata>): Boolean =
    selectedDocuments.any { !it.isBookmarked }

/**
 * The top bar shown in place of the masthead while a library multi-select is active: a count,
 * a bulk bookmark toggle, and a bulk delete action.
 *
 * @param selectedCount The number of documents currently selected, shown as the title.
 * @param bookmarkTarget The bookmark state the bulk action button will apply; see
 *   [homeSelectionBookmarkTarget]. Also chooses which icon (filled/outline) the button shows.
 * @param onCancelClick Called when the user backs out of selection mode without acting.
 * @param onBookmarkClick Called when the bulk bookmark-toggle action is tapped.
 * @param onDeleteClick Called when the bulk delete action is tapped.
 * @param modifier The modifier applied to the bar's root.
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
 * The library section of the home screen: an All/Folders toggle plus a bounded grid preview of
 * either loose documents or folder covers, with a "show all" action into the full library.
 *
 * Rendered as a [TeddSectionKind.Collection] with `fullBleed = false`: the preview is a fixed-column
 * grid rather than a horizontally scrolling shelf, so it has no reason to ignore the screen's
 * horizontal inset the way [HomeDocumentCollection]'s pager-backed shelves do.
 *
 * @param previewMode Whether the grid currently shows all documents or folders.
 * @param onPreviewModeChange Called when the All/Folders chip selection changes.
 * @param previewDocuments The (already limited) documents to show in All mode.
 * @param allDocuments The full library document list, used to compute each folder's own preview
 *   thumbnails in Folders mode.
 * @param folders The library's folders, further limited to [previewLimit] before being shown.
 * @param previewLimit How many tiles this preview may show, chosen from the available screen
 *   size (see `libraryPreviewLimit`); also selects a 2- or 4-column grid.
 * @param selectedDocumentIds The document ids currently selected for bulk action.
 * @param actionDocumentTarget The section/document whose overflow menu is currently open, if any.
 * @param documentCoverImages Pre-decoded cover bytes, keyed by document id.
 * @param onDocumentClick Called with a document id on tap.
 * @param onStartSelection Called with a document id to begin a multi-select from a long press.
 * @param onShowActions Called with a document id to open its overflow menu.
 * @param onDismissActions Called to close whichever overflow menu is open.
 * @param onBookmarkClick Called with a document to toggle its bookmark from the overflow menu.
 * @param onDeleteClick Called with a document to request its deletion from the overflow menu.
 * @param onFolderClick Called with a folder id when a folder tile is tapped.
 * @param onViewAllClick Called when the "show all" action is tapped.
 * @param onLoadCover Called with a document id whose cover should be decoded and cached.
 * @param modifier The modifier applied to the section's root.
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
    documentCoverImages: Map<String, ByteArray>,
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
 * A titled, horizontally-paged shelf of documents — used for both the favourites and recent-
 * reading collections, which share this layout and differ only in their title/description/icon and
 * the documents they carry.
 *
 * Rendered as a [TeddSectionKind.Collection] with `fullBleed = true`, so the pager's cards can scroll
 * to the screen edge while the heading above it stays aligned with every other section on the
 * screen; see [TeddSection] for why the two need different insets. [showFavoriteIcon] moves what
 * used to be a leading bookmark glyph before the title into [TeddSection]'s trailing `action` slot,
 * since a plain heading has no leading-icon slot of its own to reuse.
 *
 * @param section Which shelf this is (favourites vs. recent), passed through to
 *   [HomeDocumentPager] and combined with a document id to identify which card's overflow menu is
 *   open.
 * @param title The section's title.
 * @param description The section's supporting description text.
 * @param documents The documents shown in this shelf.
 * @param actionDocumentTarget The section/document whose overflow menu is currently open, if any.
 * @param onDocumentClick Called with a document id on tap.
 * @param onShowActions Called with a document id to open its overflow menu.
 * @param onDismissActions Called to close whichever overflow menu is open.
 * @param onBookmarkClick Called with a document to toggle its bookmark from the overflow menu.
 * @param onDeleteClick Called with a document to request its deletion from the overflow menu.
 * @param modifier The modifier applied to the section's root.
 * @param documentCoverImages Pre-decoded cover bytes, keyed by document id.
 * @param onLoadCover Called with a document id whose cover should be decoded and cached.
 * @param showFavoriteIcon Whether a bookmark icon is shown in the section heading's trailing slot,
 *   used to mark the favourites collection specifically.
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
    documentCoverImages: Map<String, ByteArray> = emptyMap(),
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
 * A fixed-width [HorizontalPager] of [DocumentCard]s, used to render a shelf's document list for
 * [HomeDocumentCollection].
 *
 * Cards here are never selectable: [DocumentCard.selected] is hard-wired to false, and no
 * long-press starts a selection. Favourites and recent reading are shortcuts into a book, not a
 * place to manage the library — selecting here would also duplicate a book that appears in both
 * this shelf and the library grid, and the bulk actions selection offers all belong to the
 * library section instead.
 *
 * @param section Which shelf this is, combined with a document id to identify an open overflow
 *   menu.
 * @param documents The documents to show, one per page.
 * @param actionDocumentTarget The section/document whose overflow menu is currently open, if any.
 * @param onDocumentClick Called with a document id on tap.
 * @param onShowActions Called with a document id to open its overflow menu.
 * @param onDismissActions Called to close whichever overflow menu is open.
 * @param onBookmarkClick Called with a document to toggle its bookmark from the overflow menu.
 * @param onDeleteClick Called with a document to request its deletion from the overflow menu.
 * @param documentCoverImages Pre-decoded cover bytes, keyed by document id.
 * @param onLoadCover Called with a document id whose cover should be decoded and cached.
 * @param modifier The modifier applied to the pager's root.
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
    documentCoverImages: Map<String, ByteArray>,
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

/** The fixed width of a document card inside [HomeDocumentPager]'s shelves. */
private val HomeDocumentCardWidth = 180.dp

/**
 * The home screen's header block: the "Library" label, app name, tagline, and the add-documents
 * and settings actions.
 *
 * @param showAddAction Whether the add-documents button is shown; hidden once the library already
 *   has documents, where the empty-state's own add action would otherwise duplicate it.
 * @param onAddDocumentsClick Called when the add-documents action is tapped.
 * @param onSettingsClick Called when the settings action is tapped.
 * @param modifier The modifier applied to the masthead's root.
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
 * The dialog offered by the add-documents action: local file/folder picking, plus Google Drive
 * when the platform supports it.
 *
 * @param onDismissRequest Called when the dialog should close without an action.
 * @param onSelectFilesClick Called when the "select files" row is tapped.
 * @param onSelectFolderClick Called when the "select folder" row is tapped.
 * @param onSelectGoogleDriveClick Called when the Google Drive row is tapped; null hides that row
 *   entirely.
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
 * The empty state shown when a library format filter matches no documents, distinct from the
 * whole-library empty state so the recovery action here clears the filter instead of importing.
 *
 * @param onShowAllClick Called when the user asks to clear the active filter.
 * @param modifier The modifier applied to the empty state's root.
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
 * The library's sort-order and format-filter chip rows, shown above the library preview grid.
 *
 * @param sort The currently selected sort order.
 * @param formatFilter The currently selected format filter.
 * @param onSortChange Called when a sort chip is tapped.
 * @param onFormatFilterChange Called when a format-filter chip is tapped.
 * @param modifier The modifier applied to the controls' root.
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
 * A labelled, wrapping row of chips — the shared layout behind both the sort and format-filter
 * rows in [HomeSortFilterControls].
 *
 * @param label The row's caption, shown above the chips.
 * @param style The text style applied to [label].
 * @param contentColor The color applied to [label].
 * @param content The chips themselves.
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
 * The localized chip label for a sort option.
 *
 * @receiver The sort option to label.
 * @return The chip's display text.
 */
@Composable
private fun HomeSort.chipLabel(): String = when (this) {
    HomeSort.Recent -> stringResource(Res.string.recent)
    HomeSort.Title -> stringResource(Res.string.title)
    HomeSort.Format -> stringResource(Res.string.format)
}

/**
 * The chip label for a format filter: the "All" option is localized, the rest are shown as the
 * format's own short code (e.g. "PDF") since those are not translated elsewhere in the app either.
 *
 * @receiver The format filter to label.
 * @return The chip's display text.
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
 * Adds [value] to this set if absent, or removes it if present — the standard toggle used to
 * flip a document's membership in the library multi-selection.
 *
 * @receiver The current set.
 * @param value The element to toggle.
 * @return A new set with [value]'s membership flipped.
 */
private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * A document's page count when known, otherwise its format name — a short line meant for a
 * supporting-text slot in a list item.
 *
 * @receiver The document to describe.
 * @return The formatted page count, or the format name when no page count is known.
 */
@Composable
private fun DocumentMetadata.supportingText(): String =
    pageCount?.let { stringResource(Res.string.document_pages, it) } ?: format.name

/** Compose preview of [HomeScreen] with no documents in the library yet. */
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
 * Compose preview of [HomeScreen] with favourites, recent reading, and a library folder
 * populated, across compact/medium/expanded widths.
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
