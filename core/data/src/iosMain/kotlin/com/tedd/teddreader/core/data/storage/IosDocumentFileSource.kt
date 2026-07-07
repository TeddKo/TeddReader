package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
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

    @OptIn(ExperimentalForeignApi::class)
    fun copyIntoAppContainer(
        sourcePath: String,
        displayName: String,
        mimeType: String? = null,
    ): DocumentLocation {
        val data = NSData.dataWithContentsOfFile(sourcePath) ?: error("Cannot open document: $sourcePath")
        val destination = "${NSHomeDirectory()}/Documents/$displayName"
        NSFileManager.defaultManager.removeItemAtPath(destination, error = null)
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
