package com.tedd.teddreader.core.room.entity

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 렌더링할 수 없는 행, 즉 이름 없는 폴더 id나 빈 폴더 레이블이 애초에 기록되지 않도록 엔티티가 생성 시 강제하는
 * 불변 조건을 고정합니다.
 */
class RoomEntityTest {
    @Test
    fun documentEntityStoresStableMetadata() {
        val document = DocumentEntity(
            id = "doc-1",
            name = "sample.txt",
            sourceUri = "file:///sample.txt",
            format = "TXT",
            addedAtEpochMillis = 1_000L,
            characterCount = 12L,
            wordCount = 2L,
        )

        assertEquals("doc-1", document.id)
        assertEquals("TXT", document.format)
        assertEquals(12L, document.characterCount)
    }

    @Test
    fun documentEntityStoresFolderMembership() {
        val document = DocumentEntity(
            id = "doc-2",
            name = "foldered.epub",
            sourceUri = "file:///foldered.epub",
            format = "EPUB",
            addedAtEpochMillis = 2_000L,
            folderId = "folder-9",
            folderName = "Sci-Fi",
        )

        assertEquals("folder-9", document.folderId)
        assertEquals("Sci-Fi", document.folderName)
    }

}
