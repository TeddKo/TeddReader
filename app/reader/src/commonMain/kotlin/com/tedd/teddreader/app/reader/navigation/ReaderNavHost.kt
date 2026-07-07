package com.tedd.teddreader.app.reader.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.tedd.teddreader.app.reader.importer.DocumentImporter
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.feature.bookmarks.api.BookmarksRoute
import com.tedd.teddreader.feature.bookmarks.impl.BookmarksRouteScreen
import com.tedd.teddreader.feature.document_info.api.DocumentInfoRoute
import com.tedd.teddreader.feature.document_info.impl.DocumentInfoRouteScreen
import com.tedd.teddreader.feature.home.api.HomeRoute
import com.tedd.teddreader.feature.home.impl.HomeRouteScreen
import com.tedd.teddreader.feature.reader.api.ReaderRoute
import com.tedd.teddreader.feature.reader.impl.ReaderRouteScreen
import com.tedd.teddreader.feature.search.api.SearchRoute
import com.tedd.teddreader.feature.search.impl.SearchRouteScreen
import com.tedd.teddreader.feature.settings.api.SettingsRoute

@Composable
fun ReaderNavHost(
    documentImporter: DocumentImporter,
    modifier: Modifier = Modifier,
    externalImportRequest: ExternalDocumentImportRequest? = null,
) {
    val backStack = remember { mutableStateListOf<Any>(HomeRoute) }
    var pendingReaderLocation by remember { mutableStateOf<ReaderLocation?>(null) }
    var homeImportMessage by remember { mutableStateOf<String?>(null) }
    var consumedExternalImport by remember { mutableStateOf<ExternalDocumentImportRequest?>(null) }

    LaunchedEffect(externalImportRequest) {
        val request = externalImportRequest ?: return@LaunchedEffect
        if (request == consumedExternalImport) return@LaunchedEffect
        consumedExternalImport = request
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
        entryProvider = { key ->
            when (key) {
                HomeRoute -> NavEntry(key) {
                    HomeRouteScreen(
                        modifier = Modifier.safeContentPadding(),
                        importMessage = homeImportMessage,
                        onOpenFileClick = {
                            documentImporter.open(
                                onImported = { documentId ->
                                    homeImportMessage = null
                                    backStack.add(ReaderRoute(documentId.value))
                                },
                                onError = { message -> homeImportMessage = message },
                            )
                        },
                        onDocumentClick = { documentId ->
                            homeImportMessage = null
                            backStack.add(ReaderRoute(documentId.value))
                        },
                    )
                }
                is ReaderRoute -> NavEntry(key) {
                    ReaderRouteScreen(
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                        onSearchClick = { backStack.add(SearchRoute(key.documentId)) },
                        onBookmarksClick = { backStack.add(BookmarksRoute(key.documentId)) },
                        onDocumentInfoClick = { backStack.add(DocumentInfoRoute(key.documentId)) },
                        jumpLocation = pendingReaderLocation,
                        onJumpLocationConsumed = { pendingReaderLocation = null },
                    )
                }
                is SearchRoute -> NavEntry(key) {
                    SearchRouteScreen(
                        modifier = Modifier.safeContentPadding(),
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                        onResultClick = { location ->
                            pendingReaderLocation = location
                            backStack.removeLastOrNull()
                        },
                    )
                }
                is BookmarksRoute -> NavEntry(key) {
                    BookmarksRouteScreen(
                        modifier = Modifier.safeContentPadding(),
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                        onBookmarkClick = { location ->
                            pendingReaderLocation = location
                            backStack.removeLastOrNull()
                        },
                    )
                }
                is DocumentInfoRoute -> NavEntry(key) {
                    DocumentInfoRouteScreen(
                        modifier = Modifier.safeContentPadding(),
                        documentId = key.documentId,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                SettingsRoute -> NavEntry(key) {
                    PlaceholderDestination(
                        modifier = Modifier.safeContentPadding(),
                        title = "Settings",
                        description = "Settings screen is not connected yet.",
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                else -> NavEntry(key) {
                    PlaceholderDestination(
                        modifier = Modifier.safeContentPadding(),
                        title = "Unknown",
                        description = "Unsupported destination.",
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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = description,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            modifier = Modifier.padding(top = 16.dp),
            onClick = onBack,
        ) {
            Text("Back")
        }
    }
}
