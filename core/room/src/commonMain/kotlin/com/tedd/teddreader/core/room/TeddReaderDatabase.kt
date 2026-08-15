package com.tedd.teddreader.core.room

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.tedd.teddreader.core.room.dao.BookmarkDao
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.ReadingProgressDao
import com.tedd.teddreader.core.room.dao.ReadingSessionDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity

@Database(
    entities = [
        DocumentEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        SearchIndexEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
@ConstructedBy(TeddReaderDatabaseConstructor::class)
abstract class TeddReaderDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun searchIndexDao(): SearchIndexDao
}

@Suppress("KotlinNoActualForExpect")
expect object TeddReaderDatabaseConstructor : RoomDatabaseConstructor<TeddReaderDatabase> {
    override fun initialize(): TeddReaderDatabase
}
