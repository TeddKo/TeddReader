package com.tedd.teddreader.core.data.parser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Android's implementation of the [defaultPdfMetadataReader] contract. */
internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = AndroidPdfMetadataReader()

/**
 * Android's [PdfMetadataReader], built on `android.graphics.pdf.PdfRenderer`. `PdfRenderer` needs a
 * [ParcelFileDescriptor], so this implementation resolves one in priority order:
 *
 * 1. When [DocumentLocation.sourceUri] is a `file://` URI pointing at a readable file, that file is
 *    opened directly — no copy, no temporary file, no redundant I/O. This is the normal path after
 *    import, since every imported document is materialized into app-private storage as a local file
 *    before this reader is ever called.
 * 2. Otherwise, [bytes] (when non-null) are written to a temporary file that is deleted immediately
 *    after use. This fallback covers the transient case where a document arrives via a `content://`
 *    URI that has not yet been materialized (e.g. a Google Drive download whose bytes are still in
 *    memory), and the legacy callers that pass bytes unconditionally.
 * 3. When both the file path is unreachable and [bytes] is null, the safe default is returned (1 for
 *    page count, null for cover) without throwing.
 */
class AndroidPdfMetadataReader : PdfMetadataReader {
    /**
     * @param location The document's location; when its [DocumentLocation.sourceUri] is a `file://`
     *   URI pointing at an existing readable file, that file is opened directly without touching
     *   [bytes].
     * @param bytes The document's raw bytes as a fallback, written to a temp file for [PdfRenderer]
     *   to open only when [location] cannot be opened directly. Null when the caller guarantees
     *   [location] is a reachable local file.
     * @return The page count, or `1` if the PDF could not be opened or rendered at all (a corrupt
     *   file, an I/O failure, or an unreachable location with no bytes fallback) — this never throws.
     */
    override fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int =
        withPdfRenderer(location, bytes) { renderer ->
            renderer.pageCount.coerceAtLeast(1)
        } ?: 1

    /**
     * @param location The document's location; when its [DocumentLocation.sourceUri] is a `file://`
     *   URI pointing at an existing readable file, that file is opened directly without touching
     *   [bytes].
     * @param bytes The document's raw bytes as a fallback, written to a temp file for [PdfRenderer]
     *   to open only when [location] cannot be opened directly. Null when the caller guarantees
     *   [location] is a reachable local file.
     * @return A PNG-encoded thumbnail of the first page, scaled down (never up) to fit within a
     *   360×480 box while preserving aspect ratio, or `null` if the document has no page, the page has
     *   no usable size, or rendering fails for any reason.
     */
    override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? =
        withPdfRenderer(location, bytes) { renderer ->
            if (renderer.pageCount <= 0) return@withPdfRenderer null
            renderer.openPage(0).use { page ->
                if (page.width <= 0 || page.height <= 0) return@withPdfRenderer null
                val scale = minOf(360f / page.width.toFloat(), 480f / page.height.toFloat(), 1f)
                val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

    /**
     * Opens a [PdfRenderer] using the location-first strategy described in this class's own doc,
     * executes [block] against it, and returns the result. The renderer and its backing
     * [ParcelFileDescriptor] are always closed, and any temporary file created for the bytes
     * fallback is always deleted.
     *
     * @param location The document's location to try opening first.
     * @param bytes Fallback bytes to materialize into a temp file when [location] is not directly
     *   openable.
     * @param block The work to do with the opened renderer.
     * @return The result of [block], or null if no renderer could be opened (location unreachable
     *   and bytes null, or any I/O failure).
     */
    private fun <T> withPdfRenderer(
        location: DocumentLocation,
        bytes: ByteArray?,
        block: (PdfRenderer) -> T,
    ): T? {
        val localFile = resolveLocalFile(location)
        if (localFile != null) {
            return try {
                ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use(block)
                }
            } catch (_: Throwable) {
                null
            }
        }
        if (bytes == null) return null
        val tempFile = File.createTempFile("tedd-reader", ".pdf")
        return try {
            tempFile.writeBytes(bytes)
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use(block)
            }
        } catch (_: Throwable) {
            null
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Resolves [location] to a local [File] when [DocumentLocation.sourceUri] is a `file://` URI
     * whose target exists and is readable. Returns null for `content://` URIs, missing files, or
     * any other scheme this implementation cannot open directly.
     *
     * @param location The document location to resolve.
     * @return A readable [File], or null when direct access is not possible.
     */
    private fun resolveLocalFile(location: DocumentLocation): File? {
        val uri = location.sourceUri
        if (!uri.startsWith("file://")) return null
        val path = uri.removePrefix("file://")
        val file = File(path)
        return file.takeIf { it.exists() && it.canRead() }
    }
}
