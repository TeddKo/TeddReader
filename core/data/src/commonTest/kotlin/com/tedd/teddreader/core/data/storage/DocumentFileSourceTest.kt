package com.tedd.teddreader.core.data.storage

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies document deletion accepts only direct children of the platform's materialized directory. */
class DocumentFileSourceTest {
    /** A direct child is the app-owned materialized file production sources may delete. */
    @Test
    fun directChildBelongsToMaterializedDirectory() {
        assertTrue(
            isDirectChildOf(
                path = "/app/documents/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** An external source beside the app directory must never be deleted with its shelf row. */
    @Test
    fun siblingDirectoryDoesNotBelongToMaterializedDirectory() {
        assertFalse(
            isDirectChildOf(
                path = "/external/documents/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** Nested files are outside the one-level materialization layout and must remain untouched. */
    @Test
    fun nestedFileDoesNotBelongToMaterializedDirectory() {
        assertFalse(
            isDirectChildOf(
                path = "/app/documents/nested/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** Normalization must prevent a traversal segment from escaping the owned directory check. */
    @Test
    fun traversalPathDoesNotBelongToMaterializedDirectory() {
        assertFalse(
            isDirectChildOf(
                path = "/app/documents/../external/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** A previous app-container UUID still identifies the same app-owned Documents directory. */
    @Test
    fun relocatedContainerDirectChildBelongsToMaterializedDirectory() {
        assertTrue(
            isDirectChildOfCurrentOrRelocatedDirectory(
                path = "/containers/old-uuid/Documents/legacy-book-2.epub".toPath(),
                currentDirectory = "/containers/current-uuid/Documents".toPath(),
            ),
        )
    }

    /** A same-named external directory outside the app-container root must remain untouched. */
    @Test
    fun unrelatedDocumentsDirectoryDoesNotBelongToRelocatedContainer() {
        assertFalse(
            isDirectChildOfCurrentOrRelocatedDirectory(
                path = "/external/Documents/legacy-book-2.epub".toPath(),
                currentDirectory = "/containers/current-uuid/Documents".toPath(),
            ),
        )
    }
}
