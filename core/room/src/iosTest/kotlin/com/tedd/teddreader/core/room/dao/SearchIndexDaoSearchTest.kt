package com.tedd.teddreader.core.room.dao

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 실제 SQLite에서 [SearchIndexDao.search]의 `ESCAPE` 절이 이스케이프된 `%`를 리터럴로 취급하는지 검증한다.
 * 가짜 DAO는 이스케이프 문자열을 해석하지 않으므로 이 계약은 실제 쿼리로만 고정할 수 있다.
 */
class SearchIndexDaoSearchTest {
    /** `100\%`로 이스케이프한 검색어가 "100% done"에만 일치하고 "1000 done"에는 일치하지 않는지 검증한다. */
    @Test
    fun escapedPercentMatchesOnlyLiteralPercent() = runTest {
        val database = Room.inMemoryDatabaseBuilder<TeddReaderDatabase>()
            .setDriver(BundledSQLiteDriver())
            .build()
        try {
            database.documentDao().upsertDocument(
                DocumentEntity(
                    id = "doc-1",
                    name = "sample.txt",
                    sourceUri = "file:///sample.txt",
                    format = "TXT",
                    addedAtEpochMillis = 1_000L,
                    characterCount = 0L,
                    wordCount = 0L,
                ),
            )
            database.searchIndexDao().upsertSearchIndex(
                listOf(
                    SearchIndexEntity("doc-1", 0, null, "100% done", 0L, 9L),
                    SearchIndexEntity("doc-1", 1, null, "1000 done", 9L, 18L),
                ),
            )

            val results = database.searchIndexDao().search("doc-1", "100\\%", 10)

            assertEquals(listOf(0), results.map { it.sectionIndex })
        } finally {
            database.close()
        }
    }
}
