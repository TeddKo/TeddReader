package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.common.model.PageWindow
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import com.tedd.teddreader.core.data.mapper.toDocumentEntity
import com.tedd.teddreader.core.data.mapper.toDocumentMetadata
import com.tedd.teddreader.core.data.mapper.toSearchIndexEntity
import com.tedd.teddreader.core.data.pagination.TextPageLayoutEngine
import com.tedd.teddreader.core.data.parser.ComicBookDocumentParser
import com.tedd.teddreader.core.data.parser.DocumentFormatDetector
import com.tedd.teddreader.core.data.parser.EpubDocumentParser
import com.tedd.teddreader.core.data.parser.ImageDocumentParser
import com.tedd.teddreader.core.data.parser.PdfDocumentParser
import com.tedd.teddreader.core.data.parser.TxtDocumentParser
import com.tedd.teddreader.core.data.parser.TxtTextDecoder
import com.tedd.teddreader.core.data.storage.DocumentFileSource
import com.tedd.teddreader.core.domain.repository.DocumentImportSource
import com.tedd.teddreader.core.domain.repository.DocumentRepository
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

@Single([DocumentRepository::class])
class DocumentRepositoryImpl(
    private val documentDao: DocumentDao,
    private val searchIndexDao: SearchIndexDao,
    private val formatDetector: DocumentFormatDetector,
    private val txtDocumentParser: TxtDocumentParser,
    private val epubDocumentParser: EpubDocumentParser,
    private val pdfDocumentParser: PdfDocumentParser,
    private val comicBookDocumentParser: ComicBookDocumentParser,
    private val imageDocumentParser: ImageDocumentParser,
    private val textPageLayoutEngine: TextPageLayoutEngine,
    private val documentFileSource: DocumentFileSource? = null,
) : DocumentRepository {
    override fun observeRecentDocuments(): Flow<List<DocumentMetadata>> =
        documentDao.observeRecentDocuments().map { documents -> documents.map { it.toDocumentMetadata() } }

    override suspend fun getDocument(documentId: DocumentId): DocumentMetadata? =
        documentDao.getDocument(documentId.value)?.toDocumentMetadata()

    override suspend fun getDocumentCover(documentId: DocumentId): ByteArray? = withContext(Dispatchers.Default) {
        val metadata = getDocument(documentId) ?: return@withContext null
        when (metadata.format) {
            DocumentFormat.TXT,
            DocumentFormat.IMAGE,
            DocumentFormat.UNKNOWN -> null
            DocumentFormat.EPUB,
            DocumentFormat.PDF,
            DocumentFormat.CBZ,
                -> {
                val fileSource = documentFileSource ?: return@withContext null
                val bytes = runCatching { fileSource.readBytes(metadata.location) }.getOrNull() ?: return@withContext null
                when (metadata.format) {
                    DocumentFormat.EPUB -> epubDocumentParser.coverImageBytes(bytes)
                    DocumentFormat.PDF -> pdfDocumentParser.coverImageBytes(metadata.location, bytes)
                    DocumentFormat.CBZ -> comicBookDocumentParser.coverImageBytes(bytes)
                    else -> null
                }
            }
        }
    }

    override suspend fun getVisualPageImages(
        documentId: DocumentId,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> = withContext(Dispatchers.Default) {
        if (pageIndexes.isEmpty()) return@withContext emptyMap()
        val metadata = getDocument(documentId) ?: return@withContext emptyMap()
        if (metadata.format != DocumentFormat.CBZ) return@withContext emptyMap()
        val fileSource = documentFileSource ?: return@withContext emptyMap()
        comicBookDocumentParser.pageImageBytes(
            bytes = fileSource.readBytes(metadata.location),
            pageIndexes = pageIndexes,
        )
    }

    override suspend fun getReaderDocument(documentId: DocumentId): ReaderDocument? {
        val metadata = getDocument(documentId) ?: return null
        val sections = getStoredSections(documentId)
        if (metadata.format == DocumentFormat.TXT && (sections.isEmpty() || sections.hasBrokenText())) {
            repairTxtDocument(metadata)?.let { return it }
        }
        return metadata.toReaderDocument(sections)
    }

    override suspend fun getPageWindows(
        documentId: DocumentId,
        style: ReaderStyle,
        viewportSize: ViewportSize,
    ): List<PageWindow> {
        val document = getReaderDocument(documentId) ?: return emptyList()
        if (document.format.isVisualPageFormat()) return emptyList()
        return textPageLayoutEngine.paginate(
            document = document,
            style = style,
            viewportSize = viewportSize,
        )
    }

    override suspend fun importDocument(
        source: DocumentImportSource,
        importedAtEpochMillis: Long,
    ): ReaderDocument {
        val id = DocumentId(source.location.sourceUri)
        val existingDocument = getDocument(id)
        val format = formatDetector.detect(source.location, source.bytes)
        val document = when (format) {
            DocumentFormat.TXT -> txtDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                text = TxtTextDecoder.decode(source.bytes),
            )

            DocumentFormat.EPUB -> epubDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                bytes = source.bytes,
            )

            DocumentFormat.PDF -> pdfDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                location = source.location,
                bytes = source.bytes,
            )

            DocumentFormat.CBZ -> comicBookDocumentParser.parse(
                id = id,
                title = source.location.displayName,
                bytes = source.bytes,
            )

            DocumentFormat.IMAGE -> imageDocumentParser.parse(
                id = id,
                title = source.location.displayName,
            )

            DocumentFormat.UNKNOWN -> throw IllegalArgumentException(
                "Unsupported document format: ${source.location.displayName}",
            )
        }

        persistParsedDocument(
            metadata = DocumentMetadata(
                id = id,
                location = source.location,
                format = document.format,
                addedAtEpochMillis = existingDocument?.addedAtEpochMillis ?: importedAtEpochMillis,
                lastOpenedAtEpochMillis = importedAtEpochMillis,
                pageCount = document.pageCount,
                characterCount = document.characterCount,
                wordCount = document.wordCount,
                isBookmarked = existingDocument?.isBookmarked ?: false,
                folderId = existingDocument?.folderId,
                folderName = existingDocument?.folderName,
            ),
            document = document,
        )
        return document
    }

    override suspend fun upsertDocument(document: DocumentMetadata) {
        documentDao.upsertDocument(document.toDocumentEntity())
    }

    override suspend fun markDocumentOpened(documentId: DocumentId, openedAtEpochMillis: Long) {
        documentDao.updateLastOpenedAt(documentId.value, openedAtEpochMillis)
    }

    override suspend fun deleteDocument(documentId: DocumentId) {
        documentDao.deleteDocument(documentId.value)
    }

    private suspend fun getStoredSections(documentId: DocumentId): List<ReaderSection> =
        searchIndexDao.getDocumentSections(documentId.value)
            .map { entry ->
                ReaderSection(
                    index = entry.sectionIndex,
                    text = entry.text,
                    range = TextRange(entry.startOffset, entry.endOffset),
                    title = entry.sectionTitle,
                )
            }

    private suspend fun repairTxtDocument(metadata: DocumentMetadata): ReaderDocument? {
        val fileSource = documentFileSource ?: return null
        return runCatching {
            val document = txtDocumentParser.parse(
                id = metadata.id,
                title = metadata.location.displayName,
                text = TxtTextDecoder.decode(fileSource.readBytes(metadata.location)),
            )
            persistParsedDocument(
                metadata = metadata.copy(
                    format = document.format,
                    pageCount = document.pageCount,
                    characterCount = document.characterCount,
                    wordCount = document.wordCount,
                ),
                document = document,
            )
            document
        }.getOrNull()
    }

    private suspend fun persistParsedDocument(metadata: DocumentMetadata, document: ReaderDocument) {
        upsertDocument(metadata)
        searchIndexDao.deleteSearchIndex(metadata.id.value)
        if (document.sections.isNotEmpty()) {
            searchIndexDao.upsertSearchIndex(
                document.sections.map { section -> section.toSearchIndexEntity(metadata.id) },
            )
        }
    }

    private fun DocumentMetadata.toReaderDocument(sections: List<ReaderSection>): ReaderDocument = ReaderDocument(
        id = id,
        format = format,
        title = location.displayName,
        sections = sections,
        pageCount = pageCount,
    )
}

private fun List<ReaderSection>.hasBrokenText(): Boolean = any { section ->
    section.text.contains('\uFFFD') || section.text.contains("ï¿½")
}
