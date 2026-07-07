package com.tedd.teddreader.core.data.parser

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.File

internal actual fun defaultPdfMetadataReader(): PdfMetadataReader = AndroidPdfMetadataReader()

class AndroidPdfMetadataReader : PdfMetadataReader {
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
}
