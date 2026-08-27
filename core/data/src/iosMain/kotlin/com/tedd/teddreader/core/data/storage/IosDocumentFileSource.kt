package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * iOS's [DocumentFileSource]: reads and copies documents by their sandboxed filesystem path,
 * addressed with a `file://` prefix the way [DocumentLocation.sourceUri] stores it consistently
 * across this class's own [materialize] and [copyIntoAppContainer].
 */
class IosDocumentFileSource : DocumentFileSource {
    /**
     * @param location The document to read.
     * @return The document's raw bytes, read via `NSData` from [location]'s resolved path.
     * @throws IllegalStateException if no file exists at [location]'s stored path or at the same file
     *   name under the current container's `Documents`.
     */
    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        val path = resolveExistingPath(location) ?: error("Cannot open document: ${location.sourceUri}")
        val data = NSData.dataWithContentsOfFile(path) ?: error("Cannot open document: $path")
        return data.toByteArray()
    }

    /**
     * The filesystem path [location] is actually readable at *now*.
     *
     * A stored `sourceUri` is an absolute path, and on iOS an absolute path into the app's own sandbox
     * does not survive: every reinstall — an Xcode/Studio rebuild onto the simulator, an App Store
     * update on a device — moves the data container to a new UUID, so the path recorded at import time
     * points into a container that no longer exists. The file itself does survive: iOS migrates the
     * container's *contents*, so the same materialized copy sits under the new container's `Documents`
     * with the same name. Resolving at read time — stored path first, then the same file name under the
     * current `Documents` — is what keeps every book readable across updates without a stored-row
     * migration.
     *
     * @param location The document whose readable path is wanted.
     * @return The stored path when it still exists, else the current-container fallback, else null.
     */
    private fun resolveExistingPath(location: DocumentLocation): String? {
        val stored = location.sourceUri.removePrefix("file://")
        if (fileSize(stored) > 0L) return stored
        val fileName = stored.substringAfterLast('/')
        if (fileName.isEmpty()) return null
        val fallback = "${NSHomeDirectory()}/Documents/$fileName"
        return fallback.takeIf { fileSize(it) > 0L }
    }

    /**
     * Copies a document from its resolved sandbox path to [destination] through the native filesystem,
     * avoiding the whole-file Kotlin [ByteArray] that large EPUB and CBZ scratch copies previously
     * retained. Existing destinations are replaced to preserve [DocumentFileSource.copyTo]'s sink-like
     * overwrite contract.
     *
     * @param location The document to copy from.
     * @param destination Where to write the copy.
     * @throws IllegalStateException if no file exists at [location]'s stored or relocated path, or the
     *   native filesystem cannot create the destination copy.
     */
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun copyTo(location: DocumentLocation, destination: okio.Path) {
        val sourcePath = resolveExistingPath(location) ?: error("Cannot open document: ${location.sourceUri}")
        FileSystem.SYSTEM.delete(destination, mustExist = false)
        check(
            NSFileManager.defaultManager.copyItemAtPath(
                srcPath = sourcePath,
                toPath = destination.toString(),
                error = null,
            ),
        ) { "Cannot copy document to $destination" }
    }

    /**
     * Writes [bytes] to this document's app-private file, skipping the write when a same-sized file
     * is already there — a cheap idempotency check that avoids rewriting the whole document on a
     * second `materialize` call for a source this app already imported.
     *
     * @param location The document's current location.
     * @param bytes The document's bytes.
     * @return [location] updated to point at the materialized `file://` path, with `sizeBytes` set to
     *   the actual bytes written.
     */
    override suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation {
        val destination = materializedPath(sourceKey = location.sourceUri, displayName = location.displayName)
        if (fileSize(destination) != bytes.size.toLong()) {
            val sink = FileSystem.SYSTEM.sink(destination.toPath()).buffer()
            try {
                sink.write(bytes)
            } finally {
                sink.close()
            }
        }
        return location.copy(
            sourceUri = "file://$destination",
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * Materializes a document straight from its original file, for a freshly picked document whose
     * bytes have not already been loaded into memory (the entry point [materialize] itself would need
     * a [ByteArray] for). This is iOS's counterpart to `AndroidDocumentFileSource.materializeFromSource`.
     *
     * The copy this document already has, when it has one, is reused rather than rewritten: reopening
     * the same book from another app used to write the whole thing again beside the first copy (see
     * [materializedDocumentFileName]). Checking the existing file's size on disk, rather than reading
     * it to measure it, is what keeps even that first copy from being read twice just to decide whether
     * a copy is needed.
     *
     * @param sourcePath The original document's filesystem path (no `file://` prefix).
     * @param displayName The document's display name, used to derive the materialized file name and
     *   stored on the returned [DocumentLocation].
     * @param mimeType The document's MIME type, if known, stored on the returned [DocumentLocation].
     * @return A [DocumentLocation] pointing at the materialized copy.
     * @throws IllegalStateException if no same-sized copy exists yet and `NSFileManager` fails to copy
     *   [sourcePath] to the materialized destination.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun copyIntoAppContainer(
        sourcePath: String,
        displayName: String,
        mimeType: String? = null,
    ): DocumentLocation {
        val destination = materializedPath(sourceKey = sourcePath, displayName = displayName)
        if (fileSize(destination) <= 0L) {
            check(
                NSFileManager.defaultManager.copyItemAtPath(
                    srcPath = sourcePath,
                    toPath = destination,
                    error = null,
                ),
            ) { "Cannot copy document: $sourcePath" }
        }
        return DocumentLocation(
            sourceUri = "file://$destination",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = fileSize(destination),
        )
    }

    /**
     * @return `Library/Caches`, not `Documents`: a cover is cheaply rebuilt from the book's own bytes
     *   on a cache miss (see `DocumentRepositoryImpl.getDocumentCover`), so unlike the reading database
     *   (`TeddReaderDatabaseBuilder.ios.kt`, which does use `Documents`) it has no reason to be backed
     *   up to iCloud or to show up in the on-device Files app.
     */
    override fun appPrivateDirectory(): okio.Path =
        "${NSHomeDirectory()}/Library/Caches".toPath()

    /**
     * The `Documents`-directory path a materialized copy of a source identified by [sourceKey] is, or
     * would be, written to.
     *
     * @param sourceKey Identifies the original source; see [materializedDocumentFileName].
     * @param displayName The document's display name; see [materializedDocumentFileName].
     * @return The materialized copy's absolute filesystem path.
     */
    private fun materializedPath(sourceKey: String, displayName: String): String =
        "${NSHomeDirectory()}/Documents/${materializedDocumentFileName(sourceKey, displayName)}"

    /** 0 for a path nothing is stored at, so "no copy yet" and "an empty copy" read the same. */
    private fun fileSize(path: String): Long =
        FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: 0L
}

/**
 * Copies this `NSData`'s bytes into a Kotlin [ByteArray].
 *
 * @receiver The data to copy.
 * @return An equal-length [ByteArray]. Empty input is special-cased to an empty array without
 *   touching native memory, since pinning a zero-length [ByteArray] and taking its address is
 *   undefined behavior on Kotlin/Native.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size == 0) return result

    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.convert())
    }
    return result
}
