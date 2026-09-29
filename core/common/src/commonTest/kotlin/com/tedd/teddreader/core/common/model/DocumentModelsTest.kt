package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 서재 행이 이름 없는 폴더에 속한다고 표시하지 못하게 하는 불변식을 고정한다. 폴더 ID와 폴더 이름은 둘 다 있거나 둘 다 없으며, 한쪽만 채운 쌍은 빈 폴더 칩으로 렌더링하지 않고 생성할 때 거부한다.
 */
class DocumentModelsTest {
    @Test
    fun documentMetadataAcceptsFolderMembershipPair() {
        val metadata = DocumentMetadata(
            id = DocumentId("doc-1"),
            location = DocumentLocation(
                sourceUri = "file:///book.epub",
                displayName = "book.epub",
            ),
            format = DocumentFormat.EPUB,
            addedAtEpochMillis = 1_000L,
            folderId = "folder-1",
            folderName = "Favorites",
        )

        assertEquals("folder-1", metadata.folderId)
        assertEquals("Favorites", metadata.folderName)
    }

    /**
     * 파싱된 문서는 생성 뒤 불변인 섹션 스냅샷의 단어 수를 최초 접근에 한 번 계산하고 이후 접근에는 같은 값을 재사용해야 한다.
     */
    @Test
    fun readerDocumentCachesWordCountAfterFirstRead() {
        val sections = mutableListOf(
            ReaderSection(
                index = 0,
                text = "hello reader",
                range = TextRange(0L, 12L),
            ),
        )
        val document = ReaderDocument(
            id = DocumentId("doc-1"),
            format = DocumentFormat.TXT,
            title = "Sample",
            sections = sections,
        )

        assertEquals(2L, document.wordCount)
        sections += ReaderSection(
            index = 1,
            text = "late mutation",
            range = TextRange(13L, 26L),
        )
        assertEquals(2L, document.wordCount)
    }

    @Test
    fun documentMetadataRejectsPartialFolderMembershipPair() {
        assertFailsWith<IllegalArgumentException> {
            DocumentMetadata(
                id = DocumentId("doc-1"),
                location = DocumentLocation(
                    sourceUri = "file:///book.epub",
                    displayName = "book.epub",
                ),
                format = DocumentFormat.EPUB,
                addedAtEpochMillis = 1_000L,
                folderId = "folder-1",
                folderName = null,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            DocumentMetadata(
                id = DocumentId("doc-1"),
                location = DocumentLocation(
                    sourceUri = "file:///book.epub",
                    displayName = "book.epub",
                ),
                format = DocumentFormat.EPUB,
                addedAtEpochMillis = 1_000L,
                folderId = null,
                folderName = "Favorites",
            )
        }
    }
}
