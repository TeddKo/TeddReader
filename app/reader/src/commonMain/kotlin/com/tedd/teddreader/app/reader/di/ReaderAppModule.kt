package com.tedd.teddreader.app.reader.di

import androidx.compose.runtime.Composable
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
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
import com.tedd.teddreader.core.domain.usecase.BuildSearchIndexUseCase
import com.tedd.teddreader.core.domain.usecase.CalculateReadingStatsUseCase
import com.tedd.teddreader.core.domain.usecase.FindInDocumentUseCase
import com.tedd.teddreader.core.domain.usecase.OpenDocumentUseCase
import com.tedd.teddreader.core.domain.usecase.ReadingSessionCalculator
import com.tedd.teddreader.core.domain.usecase.RecordReadingSessionUseCase
import com.tedd.teddreader.core.domain.usecase.RestoreReadingProgressUseCase
import com.tedd.teddreader.core.domain.usecase.SaveReadingProgressUseCase
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

internal fun readerAppModule(): Module = module {
    single { get<TeddReaderDatabase>().documentDao() }
    single { get<TeddReaderDatabase>().readingProgressDao() }
    single { get<TeddReaderDatabase>().bookmarkDao() }
    single { get<TeddReaderDatabase>().readingSessionDao() }
    single { get<TeddReaderDatabase>().searchIndexDao() }

    single { ReaderPreferencesDataSource(get()) }
    single { DocumentFormatDetector() }
    single { TxtDocumentParser() }
    single { EpubDocumentParser() }
    single { PdfDocumentParser() }
    single { TextPageLayoutEngine() }

    single<DocumentRepository> {
        DocumentRepositoryImpl(
            documentDao = get(),
            searchIndexDao = get(),
            formatDetector = get(),
            txtDocumentParser = get(),
            epubDocumentParser = get(),
            pdfDocumentParser = get(),
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

    single { OpenDocumentUseCase(documentRepository = get()) }
    single { BuildSearchIndexUseCase(searchRepository = get()) }
    single { FindInDocumentUseCase(searchRepository = get()) }
    single { ReadingSessionCalculator() }
    single { RestoreReadingProgressUseCase(readerRepository = get()) }
    single { SaveReadingProgressUseCase(readerRepository = get()) }
    single { RecordReadingSessionUseCase(readingStatsRepository = get()) }
    single { CalculateReadingStatsUseCase(readingStatsRepository = get()) }

    viewModelOf(::HomeViewModel)
    viewModelOf(::ReaderViewModel)
    viewModelOf(::ReaderSettingsViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::BookmarksViewModel)
    viewModelOf(::DocumentInfoViewModel)
}

@Composable
internal expect fun rememberPlatformReaderModule(): Module
