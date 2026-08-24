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

/**
 * Android's [DocumentFileSource]: reads and copies documents reached either through a plain `file://`
 * path or, more commonly, a Storage Access Framework `content://` Uri handed over by a document
 * picker or another app's share sheet.
 *
 * @param context Any context; only its `applicationContext` is kept, so this class never outlives
 *   (or leaks) whatever shorter-lived context it happened to be constructed with.
 */
class AndroidDocumentFileSource(
    context: Context,
) : DocumentFileSource {
    /** [context]'s application context, kept instead of [context] itself so this instance cannot outlive it. */
    private val appContext = context.applicationContext

    /** [appContext]'s content resolver, used to open, read, and persist permission on `content://` documents. */
    private val contentResolver = appContext.contentResolver

    /**
     * @param location The document to read; either scheme is accepted.
     * @return The document's raw bytes, read via [File] for a `file://` [location] or via the content
     *   resolver's `openInputStream` for anything else.
     * @throws IllegalStateException if the file does not exist or the content Uri cannot be opened.
     */
    override suspend fun readBytes(location: DocumentLocation): ByteArray =
        when (Uri.parse(location.sourceUri).scheme) {
            "file" -> File(Uri.parse(location.sourceUri).path ?: error("Cannot open document: ${location.sourceUri}"))
                .readBytes()

            else -> contentResolver.openInputStream(Uri.parse(location.sourceUri))
                ?.use { input -> input.readBytes() }
                ?: error("Cannot open document: ${location.sourceUri}")
        }

    /**
     * @param location The document to copy from; either scheme is accepted.
     * @param destination Where to write the copy.
     * @throws IllegalStateException if the file does not exist or the content Uri cannot be opened.
     */
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

    /**
     * Writes [bytes] to this document's app-private file, skipping the write when a same-sized file
     * is already there — a cheap idempotency check that avoids rewriting the whole document on a
     * second `materialize` call for a source this app already imported.
     *
     * @param location The document's current location.
     * @param bytes The document's bytes.
     * @return [location] updated to point at the materialized `file://` Uri, with `sizeBytes` set to
     *   the actual bytes written.
     */
    override suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation {
        val file = documentFile(location)
        if (file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        return location.copy(
            sourceUri = Uri.fromFile(file).toString(),
            sizeBytes = bytes.size.toLong(),
        )
    }

    /**
     * Materializes [location] straight from its original source, for a freshly picked document
     * whose bytes have not already been loaded into memory the way [materialize] requires — reading
     * it into a [ByteArray] first just to hand it back to [materialize] would hold the whole document
     * in memory twice for no reason.
     *
     * The copy this document already has, when it has one, is reused rather than rewritten: another
     * app handing the same book over a second time used to write the whole thing again under a new
     * name (see [materializedDocumentFileName]), which meant this app imported it again too. Checking
     * whether the target file already exists with any content finds what the first hand-over wrote
     * and skips both the copy and the reimport.
     *
     * @param location The document's current location, pointing at its original source.
     * @return [location] updated to point at the materialized `file://` Uri, with `sizeBytes` read
     *   back from the file on disk.
     */
    suspend fun materializeFromSource(location: DocumentLocation): DocumentLocation {
        val file = documentFile(location)
        if (!file.exists() || file.length() == 0L) copyTo(location, file.toOkioPath())
        return location.copy(
            sourceUri = Uri.fromFile(file).toString(),
            sizeBytes = file.length(),
        )
    }

    /** @return [appContext]'s files directory, i.e. [Context.getFilesDir]. */
    override fun appPrivateDirectory(): Path = appContext.filesDir.toOkioPath()

    /**
     * Persists the read permission a document picker or share-sheet Intent granted for [sourceUri],
     * so this app can reopen the same `content://` Uri after a process restart or reboot without the
     * user having to pick the document again.
     *
     * @param sourceUri The content Uri that was granted.
     * @param grantFlags The Intent's `flags`; only the read-permission flag, if present, is persisted.
     */
    fun persistReadPermission(sourceUri: String, grantFlags: Int) {
        val readFlag = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readFlag != 0) {
            contentResolver.takePersistableUriPermission(Uri.parse(sourceUri), readFlag)
        }
    }

    /**
     * The app-private file [location]'s materialized copy is stored at (or would be written to),
     * under a `documents` subdirectory of [appContext]'s files directory, created if it does not
     * already exist. Named via [materializedDocumentFileName] so the same source always resolves to
     * the same file.
     *
     * @param location The document to resolve a materialized file for.
     * @return The [File] this document's bytes are, or should be, written to.
     */
    private fun documentFile(location: DocumentLocation): File {
        val directory = File(appContext.filesDir, "documents").apply { mkdirs() }
        return File(directory, materializedDocumentFileName(location.sourceUri, location.displayName))
    }
}

/**
 * @receiver A [File] to address as an okio [Path].
 * @return This file's absolute path as an okio [Path].
 */
private fun File.toOkioPath(): Path = absolutePath.toPath()
