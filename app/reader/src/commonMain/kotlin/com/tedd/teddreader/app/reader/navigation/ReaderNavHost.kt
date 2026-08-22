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
import androidx.compose.foundation.layout.systemBarsPadding
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
import com.tedd.teddreader.core.ui.component.TeddTopBar
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

/**
 * Storage token for [HomeRoute] in the back-stack encoding [navKeyToStorageToken] and
 * [storageTokenToNavKey] convert between.
 */
private const val HOME_ROUTE_TOKEN = "home"

/**
 * Storage token prefix for a [LibraryRoute] in the back-stack encoding [navKeyToStorageToken] and
 * [storageTokenToNavKey] convert between; the route's optional folder id, if any, follows the
 * prefix.
 */
private const val LIBRARY_ROUTE_PREFIX = "library:"

/**
 * Storage token prefix for a [ReaderRoute] in the back-stack encoding [navKeyToStorageToken] and
 * [storageTokenToNavKey] convert between; the route's document id follows the prefix.
 */
private const val READER_ROUTE_PREFIX = "reader:"

/**
 * Storage token prefix for a [SearchRoute] in the back-stack encoding [navKeyToStorageToken] and
 * [storageTokenToNavKey] convert between; the route's document id follows the prefix.
 */
private const val SEARCH_ROUTE_PREFIX = "search:"

/**
 * Storage token prefix for a [BookmarksRoute] in the back-stack encoding [navKeyToStorageToken]
 * and [storageTokenToNavKey] convert between; the route's document id follows the prefix.
 */
private const val BOOKMARKS_ROUTE_PREFIX = "bookmarks:"

/**
 * Storage token prefix for a [DocumentInfoRoute] in the back-stack encoding [navKeyToStorageToken]
 * and [storageTokenToNavKey] convert between; the route's document id follows the prefix.
 */
private const val DOCUMENT_INFO_ROUTE_PREFIX = "document-info:"

/**
 * Storage token for [SettingsRoute] in the back-stack encoding [navKeyToStorageToken] and
 * [storageTokenToNavKey] convert between.
 */
private const val SETTINGS_ROUTE_TOKEN = "settings"

/**
 * Lets the navigation back stack survive process death and configuration changes: each entry's
 * navigation-key object (e.g. [com.tedd.teddreader.feature.reader.api.ReaderRoute]) is not itself
 * `Parcelable`/serializable, so [rememberSaveable] cannot save the [SnapshotStateList] directly —
 * this saver converts every entry to and from the plain string tokens [navKeyToStorageToken] and
 * [storageTokenToNavKey] define instead.
 */
private val navBackStackSaver = listSaver<SnapshotStateList<Any>, String>(
    save = { backStack -> backStack.map(::navKeyToStorageToken) },
    restore = { tokens -> tokens.mapTo(mutableStateListOf(), ::storageTokenToNavKey) },
)

/**
 * Encodes one back-stack entry's navigation key as the plain string [navBackStackSaver] persists,
 * pairing with [storageTokenToNavKey] to decode it back.
 *
 * @param key a navigation key present in [ReaderNavHost]'s back stack.
 * @return the key's string encoding.
 * @throws IllegalStateException if [key] is not one of the navigation key types this navigation
 *   host recognizes.
 */
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

/**
 * Decodes a back-stack entry from the string [navKeyToStorageToken] produced, reversing its
 * per-route-type prefix scheme.
 *
 * @param token a string previously produced by [navKeyToStorageToken].
 * @return the navigation key the token encodes.
 * @throws IllegalStateException if [token] matches none of the recognized route prefixes/tokens.
 */
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

/**
 * Decides whether a completed import should jump straight into the reader: only when the picker
 * imported exactly one document, since a multi-file or folder import has no single obvious
 * document to open and the user is left on the screen they imported from to pick one themselves.
 *
 * @param documentIds the [DocumentId]s an import batch produced.
 * @return a [ReaderRoute] for the sole imported document, or null when zero or more than one
 *   document was imported.
 */
internal fun importedDocumentRoute(documentIds: List<DocumentId>): ReaderRoute? =
    documentIds.singleOrNull()?.let { documentId -> ReaderRoute(documentId.value) }

/**
 * The composition root's navigation host: owns the back stack for every screen in the app (home,
 * library, reader, search, bookmarks, document info, settings) and is the single place import
 * results and cross-screen navigation events are translated into back-stack changes. Mounted once
 * by [com.tedd.teddreader.app.reader.TeddReaderApp] for the lifetime of the app.
 *
 * Handles the app's one piece of cross-screen state that does not belong to any single screen:
 * a location picked from search or bookmarks ([pendingReaderLocation]) needs to reach the reader
 * screen being popped back onto, and an import's success/failure message
 * ([homeImportMessage]) needs to reach the home screen being popped back onto — both are held here
 * rather than in a screen's own ViewModel because the screen that produces the value and the
 * screen that consumes it are never on screen at the same time.
 *
 * @param documentImporter the platform importer used both for on-demand imports triggered from the
 *   home screen and for handling [externalImportRequest] on first composition.
 * @param modifier applied to the underlying [NavDisplay].
 * @param externalImportRequest a document import to run once, typically one the OS handed the app
 *   through an incoming intent or share target; null when the app was opened with no document
 *   attached. Importing it always opens the resulting document in the reader, unlike an on-demand
 *   multi-file import which only does so for a single imported file (see
 *   [importedDocumentRoute]).
 */
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

/**
 * Fallback content for a navigation key [ReaderNavHost]'s `entryProvider` does not recognize —
 * reachable only if a route type is added to the back stack without a matching `entryProvider`
 * branch, or a persisted back-stack token from an older app version no longer decodes to a route
 * this version defines. Shows an explanatory message with a way back rather than crashing.
 *
 * @param title the heading shown for the unrecognized destination.
 * @param description explains to the user why nothing more specific could be shown.
 * @param onBack invoked when the user taps the back action to leave this placeholder.
 * @param modifier applied to the destination's root layout.
 * @param contentPadding padding around the description and back button, defaulting to the
 *   design system's standard screen padding.
 */
@Composable
private fun PlaceholderDestination(
    title: String,
    description: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.screenPadding),
) {
    val spacing = teddReaderSpacing()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(spacing.large),
    ) {
        TeddTopBar(title = title)
        Column(
            modifier = Modifier
                .fillMaxSize()
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
