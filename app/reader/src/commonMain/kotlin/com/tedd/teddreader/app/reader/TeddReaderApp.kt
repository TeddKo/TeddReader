package com.tedd.teddreader.app.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.app.reader.di.readerAppModule
import com.tedd.teddreader.app.reader.di.rememberPlatformReaderModule
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.app.reader.importer.GoogleDrivePickerBridge
import com.tedd.teddreader.app.reader.importer.rememberDocumentImporter
import com.tedd.teddreader.app.reader.navigation.ReaderNavHost
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.ui.ProvideTeddLocalization
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration

/**
 * The composition root of TeddReader: the single Composable both `androidApp`'s `MainActivity` and
 * iOS's `MainViewController` call to stand up the entire app. It starts this composable's own
 * [org.koin.compose.KoinApplication] — combining [com.tedd.teddreader.app.reader.di.readerAppModule]
 * with the platform module from `rememberPlatformReaderModule` — rather than a process-wide
 * `startKoin()`, so the DI graph's lifetime is tied to this Composable's own lifetime instead of
 * living for the whole process; reads the persisted [ReaderSettings] to decide dark/light and
 * localization, and hands the resolved [DocumentImporter] and any pending external import request
 * down into [ReaderNavHost], which owns navigation and screen content from here on.
 *
 * @param initialExternalImportRequest a document import that should be handled once at launch —
 *   typically a file the OS handed the app through an incoming intent or a share target — passed
 *   straight through to [ReaderNavHost] to import and open. Null means the app started plainly,
 *   with no document attached.
 * @param googleDrivePickerBridge the platform bridge that can open a Google Drive file picker and
 *   exchange the result for an access token, or null when the current platform/build has no Drive
 *   integration configured. Forwarded to [rememberDocumentImporter] so the resulting
 *   [DocumentImporter] only advertises Drive import as available when a working bridge exists.
 * @param modifier applied to the [Box] that hosts [ReaderNavHost], letting a caller size or
 *   position the whole app content.
 * @param darkTheme the platform's system dark-theme signal; consulted when the user's saved theme
 *   mode is [ReaderThemeMode.SYSTEM] or [ReaderThemeMode.PUBLISHER] — see [appUsesDarkTheme] for how
 *   it combines with that setting. Defaulted to the live [isSystemInDarkTheme] reading so callers
 *   do not need to sample it themselves.
 */
@Composable
fun TeddReaderApp(
    initialExternalImportRequest: ExternalDocumentImportRequest? = null,
    googleDrivePickerBridge: GoogleDrivePickerBridge? = null,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val appModule = remember { readerAppModule() }
    val platformModule = rememberPlatformReaderModule()

    KoinApplication(
        configuration = koinConfiguration { modules(appModule, platformModule) },
    ) {
        val documentImporter = rememberDocumentImporter(googleDrivePickerBridge = googleDrivePickerBridge)
        val readerSettingsRepository = koinInject<ReaderSettingsRepository>()
        val settings by readerSettingsRepository.settings.collectAsStateWithLifecycle(initialValue = ReaderSettings())
        val appDarkTheme = appUsesDarkTheme(
            themeMode = settings.style.themeMode,
            systemInDarkTheme = darkTheme,
        )
        ProvideTeddLocalization(appLanguage = settings.appLanguage) {
            TeddReaderTheme(
                darkTheme = appDarkTheme,
            ) {
                Box(
                    modifier = modifier.fillMaxSize().background(teddReaderColors().background),
                ) {
                    ReaderNavHost(
                        modifier = Modifier.fillMaxSize(),
                        documentImporter = documentImporter,
                        externalImportRequest = initialExternalImportRequest,
                    )
                }
            }
        }
    }
}

/**
 * Resolves the user's saved [ReaderThemeMode] and the platform's live system setting into the
 * single boolean [TeddReaderTheme] needs. [ReaderThemeMode.SYSTEM] and
 * [ReaderThemeMode.PUBLISHER] consult [systemInDarkTheme]; the publisher mode follows the system since
 * it keeps the document's own page colours and has no separate app-chrome palette of its own,
 * while [ReaderThemeMode.LIGHT], [ReaderThemeMode.SEPIA], and [ReaderThemeMode.CUSTOM] all resolve
 * to light chrome regardless of the system setting because each supplies its own reading-surface
 * palette elsewhere in the design system rather than following Material's dark scheme.
 *
 * @param themeMode the reader theme the user has chosen and had persisted in [ReaderSettings].
 * @param systemInDarkTheme the platform's current system-wide dark-theme flag, sampled once by the
 *   caller so this function stays a pure decision rather than a Composable itself.
 * @return true when the app chrome should render with [TeddReaderTheme]'s dark scheme.
 */
internal fun appUsesDarkTheme(themeMode: ReaderThemeMode, systemInDarkTheme: Boolean): Boolean =
    when (themeMode) {
        ReaderThemeMode.PUBLISHER,
        ReaderThemeMode.SYSTEM,
            -> systemInDarkTheme
        ReaderThemeMode.DARK -> true
        ReaderThemeMode.LIGHT, ReaderThemeMode.SEPIA, ReaderThemeMode.CUSTOM -> false
    }
