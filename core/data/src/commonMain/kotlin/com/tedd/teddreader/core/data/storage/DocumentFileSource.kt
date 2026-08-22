package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation
import okio.ByteString.Companion.encodeUtf8
import okio.Path

/**
 * How a document's bytes are actually reached on this platform — Android's SAF `content://` Uris and
 * plain files versus iOS's sandboxed file paths — so [DocumentRepositoryImpl] can import, reopen, and
 * copy a document without branching on platform itself.
 */
interface DocumentFileSource {
    /**
     * Reads a document's entire contents into memory.
     *
     * @param location The document to read.
     * @return The document's raw bytes.
     * @throws IllegalStateException if [location] can no longer be reached, e.g. a revoked SAF
     *   permission on Android or a moved/deleted file on either platform.
     */
    suspend fun readBytes(location: DocumentLocation): ByteArray

    /**
     * Copies a document's bytes to [destination] on the local filesystem.
     *
     * @param location The document to copy from.
     * @param destination Where to write the copy.
     * @throws IllegalStateException if [location] can no longer be reached.
     */
    suspend fun copyTo(location: DocumentLocation, destination: Path)

    /**
     * Ensures a document has a durable, app-owned copy of [bytes] on disk, returning a [location]
     * pointing at it. The default implementation is a no-op that returns [location] unchanged; the
     * platform implementations override this to actually write [bytes] into app-private storage,
     * named via [materializedDocumentFileName] so re-materializing the same source lands on the same
     * file instead of writing a duplicate.
     *
     * @param location The document's current location.
     * @param bytes The document's bytes, to be written if a durable copy does not already exist.
     * @return [location], or an updated [DocumentLocation] pointing at the materialized copy.
     */
    suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation = location

    /**
     * Root of the storage this platform keeps private to the app — [android.content.Context.filesDir]
     * on Android, the sandbox's Library/Caches on iOS. Cached cover images live under here (see
     * DocumentRepositoryImpl.getDocumentCover). This sits on the interface rather than as a bare
     * expect/actual like [systemFileSystem] because, unlike that function, Android's answer needs a
     * `Context` — the same reason [readBytes] and [copyTo] already differ by platform here instead of
     * through `expect`/`actual`.
     */
    fun appPrivateDirectory(): Path
}

/**
 * The name a document copied into app storage is given, derived from where it came from rather than
 * from an unused number or a temp-file suffix.
 *
 * Both platforms used to invent a fresh name per copy — `File.createTempFile` on Android, a `-2`, `-3`
 * suffix on iOS — so every "open with" of the same book from another app wrote another full copy of it,
 * imported it again under a new id, and left another card on the shelf. Naming the copy after the source
 * makes the second open find the first copy, which is also what lets DocumentRepositoryImpl.importDocument
 * recognise a book already on the shelf and simply open it.
 *
 * The hash is the name, not a prefix on it: the display name is stored in the database and shown from
 * there, so the file needs no human-readable name, and hashing it sidesteps every question of what a
 * filesystem will accept. The extension is kept because format detection reads it.
 */
internal fun materializedDocumentFileName(sourceKey: String, displayName: String): String {
    val name = displayName.substringAfterLast('/').substringAfterLast('\\')
    val extension = name.substringAfterLast('.', "")
        .takeIf { it.isNotBlank() && it.length <= MaxMaterializedExtensionLength && it.all(Char::isLetterOrDigit) }
    val hash = sourceKey.encodeUtf8().sha1().hex()
    return if (extension == null) hash else "$hash.$extension"
}

/**
 * Longest suffix [materializedDocumentFileName] will treat as a real file extension. A display name
 * ending in a long run of letters/digits after its last dot is more likely a version string or an
 * ID than an actual extension, so past this length it is dropped rather than kept.
 */
private const val MaxMaterializedExtensionLength = 8
