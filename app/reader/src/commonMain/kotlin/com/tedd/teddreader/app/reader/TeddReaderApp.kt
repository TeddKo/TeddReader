package com.tedd.teddreader.app.reader

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.app.reader.di.readerAppModule
import com.tedd.teddreader.app.reader.di.rememberPlatformReaderModule
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.app.reader.importer.rememberDocumentImporter
import com.tedd.teddreader.app.reader.navigation.ReaderNavHost
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.domain.repository.ReaderSettings
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.ui.ProvideTeddLocalization
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration

@Composable
fun TeddReaderApp(
    initialExternalImportRequest: ExternalDocumentImportRequest? = null,
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val appModule = remember { readerAppModule() }
    val platformModule = rememberPlatformReaderModule()

    KoinApplication(
        configuration = koinConfiguration { modules(appModule, platformModule) },
    ) {
        val documentImporter = rememberDocumentImporter()
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
                Surface(
                    modifier = modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
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

internal fun appUsesDarkTheme(themeMode: ReaderThemeMode, systemInDarkTheme: Boolean): Boolean =
    when (themeMode) {
        ReaderThemeMode.SYSTEM -> systemInDarkTheme
        ReaderThemeMode.DARK -> true
        ReaderThemeMode.LIGHT, ReaderThemeMode.SEPIA, ReaderThemeMode.CUSTOM -> false
    }
