package com.tedd.teddreader.core.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `RoomDatabase.Builder` keeps its migration list in a private field with no public getter (checked
 * against the room3-runtime 3.0.0 sources — `Builder.migrationContainer` is `private val`), so there
 * is no way to ask either platform's built database "which migrations did you register" after the
 * fact. The actual fix is structural: both `createTeddReaderDatabaseBuilder` implementations
 * (android/ios) call `.addMigrations(*TeddReaderMigrationList.toTypedArray())` against this one
 * shared list instead of each spelling out its own — so there is only one list left to get wrong.
 * This test guards that shared list instead of the unreachable builder internals.
 *
 * Guards the migration chain itself, which is the one thing a schema mistake breaks silently: an install
 * upgrading from an old version follows this list step by step, so a missing or overlapping link crashes on
 * a user's device and never in a test that only opens a fresh database.
 */
class TeddReaderMigrationListTest {
    /** `currentDatabaseVersion` below is kept in sync by hand with `@Database(version = ...)` in TeddReaderDatabase. */
    @Test
    fun migrationListCoversEveryVersionUpToTheCurrentDatabaseVersionWithNoGaps() {
        val currentDatabaseVersion = 8
        val versions = TeddReaderMigrationList.map { it.startVersion to it.endVersion }

        assertEquals((1 until currentDatabaseVersion).map { version -> version to version + 1 }, versions)
    }

    @Test
    fun migration7To8IsRegisteredExactlyOnceAndLast() {
        assertSame(TeddReaderMigration7To8, TeddReaderMigrationList.last())
        assertEquals(1, TeddReaderMigrationList.count { migration -> migration === TeddReaderMigration7To8 })
    }
}
