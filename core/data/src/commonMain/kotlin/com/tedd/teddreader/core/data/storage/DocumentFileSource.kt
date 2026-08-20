package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation
import okio.ByteString.Companion.encodeUtf8
import okio.Path

interface DocumentFileSource {
    suspend fun readBytes(location: DocumentLocation): ByteArray

    suspend fun copyTo(location: DocumentLocation, destination: Path)

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

private const val MaxMaterializedExtensionLength = 8
