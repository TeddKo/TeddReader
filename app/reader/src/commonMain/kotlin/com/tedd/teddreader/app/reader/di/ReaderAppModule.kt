package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.repository.BookmarkRepositoryImpl
import com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl
import com.tedd.teddreader.core.data.repository.ReaderRepositoryImpl
import com.tedd.teddreader.core.data.repository.ReaderSettingsRepositoryImpl
import com.tedd.teddreader.core.data.repository.ReadingStatsRepositoryImpl
import com.tedd.teddreader.core.data.repository.SearchRepositoryImpl
import com.tedd.teddreader.core.datastore.ReaderPreferencesDataSource
import com.tedd.teddreader.core.domain.repository.BookmarkRepository
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.domain.repository.ReaderRepository
import com.tedd.teddreader.core.domain.repository.ReaderSettingsRepository
import com.tedd.teddreader.core.domain.repository.ReadingStatsRepository
import com.tedd.teddreader.core.domain.repository.SearchRepository
import com.tedd.teddreader.core.domain.usecase.CreateLibraryFolderUseCase
import com.tedd.teddreader.core.domain.usecase.GetDocumentInfoUseCase
import com.tedd.teddreader.core.domain.usecase.OpenReaderDocumentUseCase
import com.tedd.teddreader.core.domain.usecase.SearchDocumentUseCase
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.feature.bookmarks.impl.BookmarksViewModel
import com.tedd.teddreader.feature.document_info.impl.DocumentInfoViewModel
import com.tedd.teddreader.feature.home.impl.HomeViewModel
import com.tedd.teddreader.feature.reader.impl.ReaderViewModel
import com.tedd.teddreader.feature.search.impl.SearchViewModel
import com.tedd.teddreader.feature.settings.impl.ReaderSettingsViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Builds the composition root's platform-independent Koin module: every dependency shared across
 * screens that does not need a platform API to construct — the DAOs pulled off the shared Room
 * database, the format detector and per-format parsers, the pagination engine, every repository
 * implementation, and every feature's ViewModel.
 *
 * The `koin-annotations` dependency and its `io.insert-koin.compiler.plugin` compiler plugin are
 * wired into this module's Gradle build, but nothing under `app/reader` carries a `@Single` or
 * `@Module` annotation, and [com.tedd.teddreader.app.reader.TeddReaderApp] never adds a
 * KSP-generated module to its `koinConfiguration { modules(...) }` call — only this function's
 * result and [rememberPlatformReaderModule]'s. Every binding below is therefore registered by hand
 * through Koin's `module { }` DSL rather than by annotation processing: a new dependency only
 * becomes reachable once someone adds a line here.
 *
 * The bindings are written leaf-first — DAOs, then the parsers and layout engine that consume only
 * DAOs and platform-supplied sources, then the repository implementations that consume those, then
 * the ViewModels that consume the repositories — purely so the file reads top-to-bottom as the
 * dependency graph it wires. Koin's `single { }` is lazy and resolves by type on first `get()`, not
 * at module-definition time, so this ordering has no effect on what actually resolves; any binding
 * could be moved anywhere in the list without changing behavior, but the order is kept meaningful
 * for the next person reading it.
 *
 * Every feature ViewModel is registered with [org.koin.core.module.dsl.viewModelOf] rather than
 * `single` because each one needs Android/Compose ViewModel lifecycle semantics — recreated per
 * navigation entry and cleared once that entry leaves the back stack, scoped by
 * `koin-core-viewmodel` — instead of the process-wide singleton lifetime `single` gives the
 * repositories above them, which would be wrong for state that belongs to one screen instance.
 *
 * @return a [Module] meant to be combined with the platform module from
 *   [rememberPlatformReaderModule]; neither module is complete by itself, since the repositories
 *   registered here depend on bindings — a `Context`-backed file source, the Room database, the
 *   preferences DataStore — that only the platform module supplies.
 */
internal fun readerAppModule(): Module = module {
    single { get<TeddReaderDatabase>().documentDao() }
    single { get<TeddReaderDatabase>().readingProgressDao() }
    single { get<TeddReaderDatabase>().bookmarkDao() }
    single { get<TeddReaderDatabase>().readingSessionDao() }
    single { get<TeddReaderDatabase>().searchIndexDao() }
    single { get<TeddReaderDatabase>().pageLayoutDao() }

    single { ReaderPreferencesDataSource(get()) }
    single { DocumentFormatDetector() }
    single { TxtDocumentParser() }
    single { EpubDocumentParser() }
    single { PdfDocumentParser() }
    single { ComicBookDocumentParser() }
    single { ImageDocumentParser() }
    single { TextPageLayoutEngine() }

    single<DocumentRepository> {
        DocumentRepositoryImpl(
            documentDao = get(),
            searchIndexDao = get(),
            pageLayoutDao = get(),
            formatDetector = get(),
            txtDocumentParser = get(),
            epubDocumentParser = get(),
            pdfDocumentParser = get(),
            comicBookDocumentParser = get(),
            imageDocumentParser = get(),
            textPageLayoutEngine = get(),
            documentFileSource = get(),
        )
    }
    single<BookmarkRepository> { BookmarkRepositoryImpl(bookmarkDao = get()) }
    single<ReaderRepository> { ReaderRepositoryImpl(progressDao = get()) }
    single<ReaderSettingsRepository> { ReaderSettingsRepositoryImpl(dataSource = get()) }
    single<ReadingStatsRepository> {
        ReadingStatsRepositoryImpl(
            readingSessionDao = get(),
            documentDao = get(),
        )
    }
    single<SearchRepository> { SearchRepositoryImpl(searchIndexDao = get()) }

    single { CreateLibraryFolderUseCase(documentRepository = get()) }
    single {
        OpenReaderDocumentUseCase(
            documentRepository = get(),
            readerRepository = get(),
            readerSettingsRepository = get(),
        )
    }
    single {
        GetDocumentInfoUseCase(
            documentRepository = get(),
            readerRepository = get(),
            readingStatsRepository = get(),
        )
    }
    single { SearchDocumentUseCase(documentRepository = get(), searchRepository = get()) }

    viewModelOf(::HomeViewModel)
    viewModelOf(::ReaderViewModel)
    viewModelOf(::ReaderSettingsViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::BookmarksViewModel)
    viewModelOf(::DocumentInfoViewModel)
}

/**
 * Supplies the half of the composition root's Koin graph that needs a platform API to build: the
 * platform's `DocumentFileSource` implementation, the Room database, and the reader-preferences
 * DataStore. [readerAppModule] cannot provide these itself because commonMain has no `Context`
 * (Android) or platform file APIs (iOS) to construct them with; each target's `actual` supplies
 * exactly the bindings [readerAppModule]'s repositories are otherwise missing.
 *
 * Declared `@Composable` so the Android `actual` can read `LocalContext.current` to build its
 * bindings, and each `actual` wraps its module in `remember` so it — and the database/DataStore
 * instances inside it — is built once per composition rather than reconstructed on every
 * recomposition.
 *
 * @return a [Module] meant to be combined with [readerAppModule]'s result inside a single
 *   `koinConfiguration`; neither module resolves the full graph on its own.
 */
@Composable
internal expect fun rememberPlatformReaderModule(): Module
