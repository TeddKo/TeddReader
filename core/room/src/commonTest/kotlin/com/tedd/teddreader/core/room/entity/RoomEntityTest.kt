package com.tedd.teddreader.core.room.entity

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
