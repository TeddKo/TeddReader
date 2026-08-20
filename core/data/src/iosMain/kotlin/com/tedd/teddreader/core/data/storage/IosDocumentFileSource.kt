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

class IosDocumentFileSource : DocumentFileSource {
    override suspend fun readBytes(location: DocumentLocation): ByteArray {
        val path = location.sourceUri.removePrefix("file://")
        val data = NSData.dataWithContentsOfFile(path) ?: error("Cannot open document: ${location.sourceUri}")
        return data.toByteArray()
    }

    override suspend fun copyTo(location: DocumentLocation, destination: okio.Path) {
        val bytes = readBytes(location)
        val sink = FileSystem.SYSTEM.sink(destination).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
    }

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

    @OptIn(ExperimentalForeignApi::class)
    fun copyIntoAppContainer(
        sourcePath: String,
        displayName: String,
        mimeType: String? = null,
    ): DocumentLocation {
        val destination = materializedPath(sourceKey = sourcePath, displayName = displayName)
        // The copy this document already has, when it has one — reopening the same book from another app
        // used to write the whole thing again beside the first copy (see materializedDocumentFileName).
        // Asking the filesystem for its size, rather than reading it to measure it, is what keeps even
        // that first copy from being read twice.
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

    override fun appPrivateDirectory(): okio.Path =
        // Caches, not Documents: a cover is cheaply rebuilt from the book's own bytes on a cache miss
        // (see DocumentRepositoryImpl.getDocumentCover), so unlike the reading database
        // (TeddReaderDatabaseBuilder.ios.kt, which does use Documents) it has no reason to be backed up
        // to iCloud or to show up in the on-device Files app.
        "${NSHomeDirectory()}/Library/Caches".toPath()

    private fun materializedPath(sourceKey: String, displayName: String): String =
        "${NSHomeDirectory()}/Documents/${materializedDocumentFileName(sourceKey, displayName)}"

    /** 0 for a path nothing is stored at, so "no copy yet" and "an empty copy" read the same. */
    private fun fileSize(path: String): Long =
        FileSystem.SYSTEM.metadataOrNull(path.toPath())?.size ?: 0L
}

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
