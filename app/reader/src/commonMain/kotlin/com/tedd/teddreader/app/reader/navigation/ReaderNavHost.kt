package com.tedd.teddreader.app.reader.navigation

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.tedd.teddreader.app.reader.importer.DocumentImporter
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.parseReaderLocation
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderMotion
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import com.tedd.teddreader.feature.bookmarks.api.BookmarksRoute
import com.tedd.teddreader.feature.bookmarks.impl.BookmarksRouteScreen
import com.tedd.teddreader.feature.document_info.api.DocumentInfoRoute
import com.tedd.teddreader.feature.document_info.impl.DocumentInfoRouteScreen
import com.tedd.teddreader.feature.home.api.HomeRoute
import com.tedd.teddreader.feature.home.api.LibraryRoute
import com.tedd.teddreader.feature.home.impl.HomeRouteScreen
import com.tedd.teddreader.feature.home.impl.LibraryRouteScreen
import com.tedd.teddreader.feature.reader.api.ReaderRoute
import com.tedd.teddreader.feature.reader.impl.ReaderRouteScreen
import com.tedd.teddreader.feature.search.api.SearchRoute
import com.tedd.teddreader.feature.settings.api.SettingsRoute
import com.tedd.teddreader.feature.search.impl.SearchRouteScreen
import com.tedd.teddreader.feature.settings.impl.ReaderSettingsRouteScreen

private const val HOME_ROUTE_TOKEN = "home"
private const val LIBRARY_ROUTE_PREFIX = "library:"
private const val READER_ROUTE_PREFIX = "reader:"
private const val SEARCH_ROUTE_PREFIX = "search:"
private const val BOOKMARKS_ROUTE_PREFIX = "bookmarks:"
private const val DOCUMENT_INFO_ROUTE_PREFIX = "document-info:"
private const val SETTINGS_ROUTE_TOKEN = "settings"

private val navBackStackSaver = listSaver<SnapshotStateList<Any>, String>(
    save = { backStack -> backStack.map(::navKeyToStorageToken) },
    restore = { tokens -> tokens.mapTo(mutableStateListOf(), ::storageTokenToNavKey) },
)

internal fun navKeyToStorageToken(key: Any): String = when (key) {
    HomeRoute -> HOME_ROUTE_TOKEN
    is LibraryRoute -> if (key.folderId == null) LIBRARY_ROUTE_PREFIX else LIBRARY_ROUTE_PREFIX + key.folderId
    is ReaderRoute -> READER_ROUTE_PREFIX + key.documentId
    is SearchRoute -> SEARCH_ROUTE_PREFIX + key.documentId
    is BookmarksRoute -> BOOKMARKS_ROUTE_PREFIX + key.documentId
    is DocumentInfoRoute -> DOCUMENT_INFO_ROUTE_PREFIX + key.documentId
    SettingsRoute -> SETTINGS_ROUTE_TOKEN
    else -> error("Unsupported navigation key: $key")
}

internal fun storageTokenToNavKey(token: String): Any = when {
    token == HOME_ROUTE_TOKEN -> HomeRoute
    token == LIBRARY_ROUTE_PREFIX -> LibraryRoute()
    token.startsWith(LIBRARY_ROUTE_PREFIX) -> LibraryRoute(token.removePrefix(LIBRARY_ROUTE_PREFIX).ifBlank { null })
    token.startsWith(READER_ROUTE_PREFIX) -> ReaderRoute(token.removePrefix(READER_ROUTE_PREFIX))
    token.startsWith(SEARCH_ROUTE_PREFIX) -> SearchRoute(token.removePrefix(SEARCH_ROUTE_PREFIX))
    token.startsWith(BOOKMARKS_ROUTE_PREFIX) -> BookmarksRoute(token.removePrefix(BOOKMARKS_ROUTE_PREFIX))
    token.startsWith(DOCUMENT_INFO_ROUTE_PREFIX) ->
        DocumentInfoRoute(token.removePrefix(DOCUMENT_INFO_ROUTE_PREFIX))
    token == SETTINGS_ROUTE_TOKEN -> SettingsRoute
    else -> error("Unsupported navigation token: $token")
}

internal fun importedDocumentRoute(documentIds: List<DocumentId>): ReaderRoute? =
    documentIds.singleOrNull()?.let { documentId -> ReaderRoute(documentId.value) }

@Composable
fun ReaderNavHost(
    documentImporter: DocumentImporter,
    modifier: Modifier = Modifier,
    externalImportRequest: ExternalDocumentImportRequest? = null,
) {
    val backStack = rememberSaveable(saver = navBackStackSaver) { mutableStateListOf<Any>(HomeRoute) }
    var pendingReaderLocation by rememberSaveable { mutableStateOf<String?>(null) }
    var homeImportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var consumedExternalImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    val navigationAnimationDurationMs = teddReaderMotion().mediumDurationMs

    LaunchedEffect(externalImportRequest) {
        val request = externalImportRequest ?: return@LaunchedEffect
        if (request.sourceUri == consumedExternalImportUri) return@LaunchedEffect
        consumedExternalImportUri = request.sourceUri
        documentImporter.importExternal(
            request = request,
            onImported = { documentId ->
                homeImportMessage = null
                backStack.add(ReaderRoute(documentId.value))
            },
            onError = { message -> homeImportMessage = message },
        )
    }

    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        },
        transitionSpec = {
            slideIntoContainer(SlideDirection.Start, tween(navigationAnimationDurationMs)) togetherWith
                slideOutOfContainer(SlideDirection.Start, tween(navigationAnimationDurationMs))
        },
        popTransitionSpec = {
            slideIntoContainer(SlideDirection.End, tween(navigationAnimationDurationMs)) togetherWith
                slideOutOfContainer(SlideDirection.End, tween(navigationAnimationDurationMs))
        },
        predictivePopTransitionSpec = { _ ->
            slideIntoContainer(SlideDirection.End, tween(navigationAnimationDurationMs)) togetherWith
                slideOutOfContainer(SlideDirection.End, tween(navigationAnimationDurationMs))
        },
        entryProvider = { key ->
            when (key) {
                HomeRoute -> NavEntry(key) {
                    HomeRouteScreen(
                        importMessage = homeImportMessage,
                        onSettingsClick = { backStack.add(SettingsRoute) },
                        onOpenFilesClick = {
                            documentImporter.openFiles(
                                onImported = { documentIds ->
                                    homeImportMessage = null
                                    importedDocumentRoute(documentIds)?.let(backStack::add)
                                },
                                onError = { message -> homeImportMessage = message },
                            )
                        },
                        onOpenFolderClick = {
                            documentImporter.openFolder(
                                onImported = { documentIds ->
                                    homeImportMessage = null
                                    importedDocumentRoute(documentIds)?.let(backStack::add)
                                },
                                onError = { message -> homeImportMessage = message },
                            )
                        },
                        onOpenGoogleDriveClick = documentImporter
                            .takeIf { it.supportsGoogleDrivePicker }
                            ?.let {
                                {
                                    it.openGoogleDrive(
                                        onImported = { documentIds ->
                                            homeImportMessage = null
                                            importedDocumentRoute(documentIds)?.let(backStack::add)
                                        },
                                        onError = { message -> homeImportMessage = message },
                                    )
                                }
                            },
                        onDocumentClick = { documentId ->
                            homeImportMessage = null
                            backStack.add(ReaderRoute(documentId.value))
                        },
                        onOpenLibraryClick = { backStack.add(LibraryRoute()) },
                        onOpenLibraryFolderClick = { folderId -> backStack.add(LibraryRoute(folderId)) },
                    )
                }

                is LibraryRoute -> NavEntry(key) {
                    LibraryRouteScreen(
                        folderId = key.folderId,
                        onBack = { backStack.removeLastOrNull() },
                        onDocumentClick = { documentId -> backStack.add(ReaderRoute(documentId.value)) },
                        onFolderClick = { folderId -> backStack.add(LibraryRoute(folderId)) },
                    )
                }

                is ReaderRoute -> NavEntry(key) {
                    ReaderRouteScreen(
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                        onSearchClick = { backStack.add(SearchRoute(key.documentId)) },
                        onBookmarksClick = { backStack.add(BookmarksRoute(key.documentId)) },
                        onDocumentInfoClick = { backStack.add(DocumentInfoRoute(key.documentId)) },
                        jumpLocation = pendingReaderLocation?.let(::parseReaderLocation),
                        onJumpLocationConsumed = { pendingReaderLocation = null },
                    )
                }

                is SearchRoute -> NavEntry(key) {
                    SearchRouteScreen(
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                        onResultClick = { location ->
                            pendingReaderLocation = location.asStorageString()
                            backStack.removeLastOrNull()
                        },
                    )
                }

                is BookmarksRoute -> NavEntry(key) {
                    BookmarksRouteScreen(
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                        onBookmarkClick = { location ->
                            pendingReaderLocation = location.asStorageString()
                            backStack.removeLastOrNull()
                        },
                    )
                }

                is DocumentInfoRoute -> NavEntry(key) {
                    DocumentInfoRouteScreen(
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }

                SettingsRoute -> NavEntry(key) {
                    ReaderSettingsRouteScreen(onBack = { backStack.removeLastOrNull() })
                }

                else -> NavEntry(key) {
                    PlaceholderDestination(
                        title = stringResource(Res.string.unsupported_destination_title),
                        description = stringResource(Res.string.unsupported_destination_description),
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
        },
    )
}

@Composable
private fun PlaceholderDestination(
    title: String,
    description: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = title,
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        Icon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TeddButton(
                text = stringResource(Res.string.back),
                onClick = onBack,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        }
    }
}
