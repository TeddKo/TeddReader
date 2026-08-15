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

    override suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation {
        val destination = uniqueDestinationPath(location.displayName)
        val sink = FileSystem.SYSTEM.sink(destination.toPath()).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
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
        val data = NSData.dataWithContentsOfFile(sourcePath) ?: error("Cannot open document: $sourcePath")
        val destination = uniqueDestinationPath(displayName)
        check(
            NSFileManager.defaultManager.copyItemAtPath(
                srcPath = sourcePath,
                toPath = destination,
                error = null,
            ),
        ) { "Cannot copy document: $sourcePath" }
        return DocumentLocation(
            sourceUri = "file://$destination",
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = data.length.toLong(),
        )
    }

    private fun uniqueDestinationPath(displayName: String): String {
        val directory = "${NSHomeDirectory()}/Documents"
        val safeDisplayName = displayName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "document"
        val dotIndex = safeDisplayName.lastIndexOf('.')
        val name = if (dotIndex > 0) safeDisplayName.substring(0, dotIndex) else safeDisplayName
        val extension = if (dotIndex > 0) safeDisplayName.substring(dotIndex) else ""
        var candidate = "$directory/$safeDisplayName"
        var suffix = 2
        while (NSFileManager.defaultManager.fileExistsAtPath(candidate)) {
            candidate = "$directory/$name-$suffix$extension"
            suffix += 1
        }
        return candidate
    }
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
