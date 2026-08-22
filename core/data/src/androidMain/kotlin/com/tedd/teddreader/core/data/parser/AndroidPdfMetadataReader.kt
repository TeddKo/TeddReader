package com.tedd.teddreader.core.data.parser

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.File
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Android's implementation of the [defaultPdfMetadataReader] contract. */
internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = AndroidPdfMetadataReader()

/**
 * Android's [PdfMetadataReader], built on `android.graphics.pdf.PdfRenderer`. `PdfRenderer` needs a
 * [ParcelFileDescriptor] rather than a byte buffer, so every call here re-materializes [bytes] into a
 * temporary file and deletes it again afterwards; `location` itself is never consulted, since the
 * bytes are all this implementation needs.
 */
class AndroidPdfMetadataReader : PdfMetadataReader {
    /**
     * @param location Unused by this implementation.
     * @param bytes The document's raw bytes, written to a temp file for [PdfRenderer] to open.
     * @return The page count, or `1` if the PDF could not be opened or rendered at all (a corrupt
     *   file, an I/O failure) — this never throws.
     */
    override fun pageCount(location: DocumentLocation, bytes: ByteArray): Int {
        val file = File.createTempFile("tedd-reader", ".pdf")
        return try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount.coerceAtLeast(1) }
            }
        } catch (_: Throwable) {
            1
        } finally {
            file.delete()
        }
    }

    /**
     * @param location Unused by this implementation.
     * @param bytes The document's raw bytes, written to a temp file for [PdfRenderer] to open.
     * @return A PNG-encoded thumbnail of the first page, scaled down (never up) to fit within a
     *   360×480 box while preserving aspect ratio, or `null` if the document has no page, the page has
     *   no usable size, or rendering fails for any reason.
     */
    override fun coverImageBytes(location: DocumentLocation, bytes: ByteArray): ByteArray? {
        val file = File.createTempFile("tedd-reader", ".pdf")
        return try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) return null
                    renderer.openPage(0).use { page ->
                        if (page.width <= 0 || page.height <= 0) return null
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
            }
        } catch (_: Throwable) {
            null
        } finally {
            file.delete()
        }
    }
}
