package com.tedd.teddreader.core.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.File
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.Path.Companion.toPath
import okio.source

class AndroidDocumentFileSource(
    context: Context,
) : DocumentFileSource {
    private val appContext = context.applicationContext
    private val contentResolver = appContext.contentResolver

    override suspend fun readBytes(location: DocumentLocation): ByteArray =
        when (Uri.parse(location.sourceUri).scheme) {
            "file" -> File(Uri.parse(location.sourceUri).path ?: error("Cannot open document: ${location.sourceUri}"))
                .readBytes()

            else -> contentResolver.openInputStream(Uri.parse(location.sourceUri))
                ?.use { input -> input.readBytes() }
                ?: error("Cannot open document: ${location.sourceUri}")
        }

    override suspend fun copyTo(location: DocumentLocation, destination: Path) {
        when (Uri.parse(location.sourceUri).scheme) {
            "file" -> FileSystem.SYSTEM.source(
                File(Uri.parse(location.sourceUri).path ?: error("Cannot open document: ${location.sourceUri}")).toOkioPath(),
            ).buffer().use { source ->
                FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
                    sink.writeAll(source)
                }
            }

            else -> contentResolver.openInputStream(Uri.parse(location.sourceUri))
                ?.use { input ->
                    FileSystem.SYSTEM.sink(destination).buffer().use { sink ->
                        sink.writeAll(input.source())
                    }
                }
                ?: error("Cannot open document: ${location.sourceUri}")
        }
    }

    override suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation {
        val file = createDocumentFile(location)
        file.writeBytes(bytes)
        return location.copy(
            sourceUri = Uri.fromFile(file).toString(),
            sizeBytes = bytes.size.toLong(),
        )
    }

    suspend fun materializeFromSource(location: DocumentLocation): DocumentLocation {
        val file = createDocumentFile(location)
        copyTo(location, file.toOkioPath())
        return location.copy(
            sourceUri = Uri.fromFile(file).toString(),
            sizeBytes = file.length(),
        )
    }

    fun persistReadPermission(sourceUri: String, grantFlags: Int) {
        val readFlag = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            contentResolver.takePersistableUriPermission(Uri.parse(sourceUri), readFlag)
        }
    }

    private fun createDocumentFile(location: DocumentLocation): File {
        val directory = File(appContext.filesDir, "documents").apply { mkdirs() }
        val displayName = location.displayName.substringAfterLast('/').ifBlank { "document" }
        val prefix = displayName.substringBeforeLast('.', displayName).take(40).ifBlank { "document" }
        val suffix = displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".${it}" } ?: ""
        return File.createTempFile(prefix.safePrefix(), suffix, directory)
    }
}

private fun String.safePrefix(): String = filter(Char::isLetterOrDigit)
    .takeIf { it.length >= 3 }
    ?: "doc"

private fun File.toOkioPath(): Path = absolutePath.toPath()
