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
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderMotion
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddButton
import com.tedd.teddreader.core.ui.component.TeddButtonEmphasis
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddText
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

/**
 * [navKeyToStorageToken]과 [storageTokenToNavKey]가 변환하는 백 스택 인코딩에서 [HomeRoute]를
 * 나타내는 저장 토큰이다.
 */
private const val HOME_ROUTE_TOKEN = "home"

/**
 * [navKeyToStorageToken]과 [storageTokenToNavKey]가 변환하는 백 스택 인코딩에서 [LibraryRoute]를
 * 나타내는 저장 토큰 접두사다. 선택적 폴더 id가 있으면 접두사 뒤에 붙는다.
 */
private const val LIBRARY_ROUTE_PREFIX = "library:"

/**
 * [navKeyToStorageToken]과 [storageTokenToNavKey]가 변환하는 백 스택 인코딩에서 [ReaderRoute]를
 * 나타내는 저장 토큰 접두사다. 문서 id가 접두사 뒤에 붙는다.
 */
private const val READER_ROUTE_PREFIX = "reader:"

/**
 * [navKeyToStorageToken]과 [storageTokenToNavKey]가 변환하는 백 스택 인코딩에서 [SearchRoute]를
 * 나타내는 저장 토큰 접두사다. 문서 id가 접두사 뒤에 붙는다.
 */
private const val SEARCH_ROUTE_PREFIX = "search:"

/**
 * [navKeyToStorageToken]과 [storageTokenToNavKey]가 변환하는 백 스택 인코딩에서 [BookmarksRoute]를
 * 나타내는 저장 토큰 접두사다. 문서 id가 접두사 뒤에 붙는다.
 */
private const val BOOKMARKS_ROUTE_PREFIX = "bookmarks:"

/**
 * [navKeyToStorageToken]과 [storageTokenToNavKey]가 변환하는 백 스택 인코딩에서
 * [DocumentInfoRoute]를 나타내는 저장 토큰 접두사다. 문서 id가 접두사 뒤에 붙는다.
 */
private const val DOCUMENT_INFO_ROUTE_PREFIX = "document-info:"

/**
 * [navKeyToStorageToken]과 [storageTokenToNavKey]가 변환하는 백 스택 인코딩에서 [SettingsRoute]를
 * 나타내는 저장 토큰이다.
 */
private const val SETTINGS_ROUTE_TOKEN = "settings"

/**
 * 내비게이션 백 스택이 프로세스 종료와 구성 변경 후에도 유지되게 한다. 각 항목의 내비게이션 키 객체
 * (예: [com.tedd.teddreader.feature.reader.api.ReaderRoute])는 자체적으로
 * `Parcelable`/serializable이 아니어서 [rememberSaveable]이 [SnapshotStateList]를 직접 저장할 수
 * 없다. 따라서 이 saver는 각 항목을 [navKeyToStorageToken]과 [storageTokenToNavKey]가 정의한 일반
 * 문자열 토큰으로 변환하고 복원한다.
 */
private val navBackStackSaver = listSaver<SnapshotStateList<Any>, String>(
    save = { backStack -> backStack.map(::navKeyToStorageToken) },
    restore = { tokens -> tokens.mapTo(mutableStateListOf(), ::storageTokenToNavKey) },
)

/**
 * 백 스택 항목의 내비게이션 키를 [navBackStackSaver]가 저장하는 일반 문자열로 인코딩한다.
 * [storageTokenToNavKey]가 이 문자열을 다시 디코딩한다.
 *
 * @param key [ReaderNavHost]의 백 스택에 있는 내비게이션 키다.
 * @return 키의 문자열 인코딩이다.
 * @throws IllegalStateException [key]가 이 내비게이션 호스트가 인식하는 내비게이션 키 타입이 아닐 때
 *   발생한다.
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
 * [navKeyToStorageToken]이 만든 문자열에서 경로 타입별 접두사 규칙을 역으로 적용하여 백 스택 항목을
 * 디코딩한다.
 *
 * @param token 이전에 [navKeyToStorageToken]이 만든 문자열이다.
 * @return 토큰이 인코딩한 내비게이션 키다.
 * @throws IllegalStateException [token]이 인식하는 어떤 경로 접두사/토큰과도 일치하지 않을 때
 *   발생한다.
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
 * 완료된 가져오기 후 리더로 바로 이동할지 결정한다. 여러 파일이나 폴더 가져오기에는 바로 열 명확한
 * 단일 문서가 없으므로 선택기가 정확히 한 문서만 가져왔을 때만 이동한다. 그 외에는 사용자가 직접
 * 문서를 고를 수 있도록 가져오기를 시작한 화면에 남긴다.
 *
 * @param documentIds 가져오기 배치가 만든 [DocumentId]다.
 * @return 가져온 문서가 하나면 해당 문서의 [ReaderRoute], 0개이거나 둘 이상이면 null이다.
 */
internal fun importedDocumentRoute(documentIds: List<DocumentId>): ReaderRoute? =
    documentIds.singleOrNull()?.let { documentId -> ReaderRoute(documentId.value) }

/**
 * 컴포지션 루트의 내비게이션 호스트다. 앱의 모든 화면(홈, 라이브러리, 리더, 검색, 북마크, 문서 정보,
 * 설정)에 대한 백 스택을 소유하며, 가져오기 결과와 화면 간 내비게이션 이벤트를 백 스택 변경으로
 * 변환하는 유일한 위치다. 앱 수명 동안 [com.tedd.teddreader.app.reader.TeddReaderApp]이 한 번
 * 배치한다.
 *
 * 어느 한 화면에도 속하지 않는 앱의 화면 간 상태를 처리한다. 검색이나 북마크에서 선택한 위치
 * ([pendingReaderLocation])는 팝으로 돌아갈 리더 화면에 전달되어야 하고, 가져오기 성공/실패 메시지
 * ([homeImportMessage])는 팝으로 돌아갈 홈 화면에 전달되어야 한다. 값을 만드는 화면과 사용하는
 * 화면이 동시에 표시되지 않으므로 각 화면의 ViewModel이 아니라 여기에 보관한다.
 *
 * @param documentImporter 홈 화면에서 실행하는 주문형 가져오기와 첫 컴포지션의
 *   [externalImportRequest] 처리에 모두 사용하는 플랫폼 임포터다.
 * @param modifier 내부 [NavDisplay]에 적용할 수정자다.
 * @param externalImportRequest 한 번 실행할 문서 가져오기 요청으로, 일반적으로 OS가 수신 인텐트나
 *   공유 대상으로 앱에 전달한 문서다. 첨부 문서 없이 앱을 열었으면 null이다. 주문형 다중 파일
 *   가져오기가 단일 파일일 때만 여는 것과 달리, 이 요청은 결과 문서를 항상 리더에서 연다
 *   ([importedDocumentRoute] 참고).
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
 * [ReaderNavHost]의 `entryProvider`가 인식하지 못하는 내비게이션 키를 위한 대체 콘텐츠다. 일치하는
 * `entryProvider` 분기 없이 경로 타입을 백 스택에 추가했거나, 이전 앱 버전에서 저장한 백 스택 토큰을
 * 이 버전이 정의한 경로로 더는 디코딩하지 못할 때만 도달한다. 충돌하는 대신 이유를 설명하고 돌아갈
 * 방법을 제공한다.
 *
 * @param title 인식하지 못한 목적지에 표시할 제목이다.
 * @param description 더 구체적인 콘텐츠를 표시할 수 없는 이유를 사용자에게 설명한다.
 * @param onBack 사용자가 뒤로 가기 동작을 눌러 이 대체 화면을 떠날 때 호출한다.
 * @param modifier 목적지의 루트 레이아웃에 적용할 수정자다.
 * @param contentPadding 설명과 뒤로 가기 버튼 주위의 여백이다. null이면 디자인 시스템의 표준 화면
 *   여백을 사용한다.
 */
@Composable
private fun PlaceholderDestination(
    title: String,
    description: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(spacing.screenPadding)

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = title,
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .padding(resolvedContentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            TeddText(
                text = description,
                style = teddReaderTypography().bodyMedium,
                color = teddReaderColors().onSurfaceVariant,
            )
            TeddButton(
                text = stringResource(Res.string.back),
                onClick = onBack,
                emphasis = TeddButtonEmphasis.Secondary,
            )
        }
    }
}
