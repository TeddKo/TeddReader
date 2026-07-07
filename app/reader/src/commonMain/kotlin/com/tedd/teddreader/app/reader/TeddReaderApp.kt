package com.tedd.teddreader.app.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.tedd.teddreader.app.reader.di.readerAppModule
import com.tedd.teddreader.app.reader.di.rememberPlatformReaderModule
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.app.reader.importer.rememberDocumentImporter
import com.tedd.teddreader.app.reader.navigation.ReaderNavHost
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

@Composable
fun TeddReaderApp(
    initialExternalImportRequest: ExternalDocumentImportRequest? = null,
) {
    val appModule = remember { readerAppModule() }
    val platformModule = rememberPlatformReaderModule()

    KoinApplication(
        configuration = koinConfiguration { modules(appModule, platformModule) },
    ) {
        val documentImporter = rememberDocumentImporter()
        TeddReaderTheme {
            ReaderNavHost(
                modifier = Modifier.fillMaxSize(),
                documentImporter = documentImporter,
                externalImportRequest = initialExternalImportRequest,
            )
        }
    }
}
