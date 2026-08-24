package com.tedd.teddreader.core.room

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.tedd.teddreader.core.room.dao.BookmarkDao
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.ReadingProgressDao
import com.tedd.teddreader.core.room.dao.ReadingSessionDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity

/**
 * The app's single SQLite database, holding the library and everything derived from it.
 *
 * Six tables, one purpose each: what documents exist, where each is being read, what places are saved,
 * how long they were read, their searchable text, and their measured page layouts. They live in one
 * database rather than several because every one of them is keyed by document and deleted with it.
 *
 * `exportSchema = false` because migrations here are hand-written and covered by
 * TeddReaderMigrationListTest, which checks the chain rather than a generated schema dump.
 *
 * Both platforms construct this through generated code (`@ConstructedBy`) and open it with the bundled
 * SQLite driver — see the platform `createTeddReaderDatabaseBuilder` — so Android and iOS run the same
 * SQLite build and the same migration list rather than whatever the OS ships.
 */
@Database(
    entities = [
        DocumentEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        SearchIndexEntity::class,
        PageLayoutEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@ConstructedBy(TeddReaderDatabaseConstructor::class)
abstract class TeddReaderDatabase : RoomDatabase() {
    /** The library table — what documents exist, and their ordering. */
    abstract fun documentDao(): DocumentDao
    /** Where each document is being read; one row per document. */
    abstract fun readingProgressDao(): ReadingProgressDao
    /** Saved places, per document. */
    abstract fun bookmarkDao(): BookmarkDao
    /** Reading sessions and their summed active time. Nothing writes to it yet. */
    abstract fun readingSessionDao(): ReadingSessionDao
    /** Stored document text: the reader's source as well as the search index. */
    abstract fun searchIndexDao(): SearchIndexDao
    /** Cached page measurements, keyed by document, type and viewport. */
    abstract fun pageLayoutDao(): PageLayoutDao
}

/**
 * Room generates the actual object per platform, which is why the expect declaration has no visible
 * actual here and the compiler's complaint about that is suppressed.
 */
@Suppress("KotlinNoActualForExpect")
expect object TeddReaderDatabaseConstructor : RoomDatabaseConstructor<TeddReaderDatabase> {
    /**
     * Room's own KSP-generated implementation of this, produced per platform from the
     * `@Database`/`@ConstructedBy` annotations above — there is no hand-written body to read here.
     * It only builds a bare instance of the generated database class; the platform
     * `createTeddReaderDatabaseBuilder` (see class doc) is what opens it against a real file, wires
     * the SQLite driver, and drives the migration chain.
     *
     * @return a fresh [TeddReaderDatabase] instance, not yet opened against a file or migrated.
     */
    override fun initialize(): TeddReaderDatabase
}
