package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.SupportedDocumentExtensions
import com.tedd.teddreader.core.common.model.SupportedDocumentMimeTypes
import org.koin.core.annotation.Single

/**
 * Decides which [DocumentFormat] a file is, from whatever a source is willing to give: a display name,
 * a self-reported MIME type, and (optionally) the file's own leading bytes.
 *
 * Neither name nor MIME type is trustworthy on its own — a cloud provider can report a generic MIME
 * type like `application/octet-stream`, and a document picked up from a content URI may carry no file
 * extension at all — so this checks both, and for PDF and raster images also sniffs the file's magic
 * bytes as a fallback that neither of the other two can be wrong about. [bytes] is nullable because a
 * caller that only has a name and a MIME type (nothing has been read yet) can still ask; in that case
 * detection falls back to name/MIME matching alone and simply cannot resolve a case that needed the
 * signature.
 */
@Single
class DocumentFormatDetector {
    /**
     * Classifies the file at [location], optionally confirmed against its own [bytes].
     *
     * Formats are tried in a fixed order and the first match wins: TXT, then PDF, then EPUB, then CBZ,
     * then a raster IMAGE, falling through to [DocumentFormat.UNKNOWN] when nothing matches. CBZ is
     * recognized only by a comic-specific MIME type (`application/vnd.comicbook+zip`,
     * `application/x-cbz`) or a literal `.cbz` extension — deliberately not by sniffing the ZIP
     * signature every CBZ shares with a plain `.zip`, a `.docx`, or a `.mobi`, any of which would
     * otherwise be misdetected as a comic. IMAGE covers only the raster formats this reader can
     * actually decode (JPEG, PNG, WebP, GIF, BMP); an SVG is a document [SupportedDocumentMimeTypes]
     * never lists and whose bytes match none of [hasRasterImageSignature]'s signatures, so it resolves
     * to UNKNOWN here even when a caller reports `image/svg+xml`.
     *
     * @param location the file's own account of itself: [DocumentLocation.displayName] (read for its
     *   extension) and [DocumentLocation.mimeType] (may be generic, missing, or absent entirely).
     * @param bytes the file's leading bytes, or null when nothing has been read yet. Used only as a
     *   fallback signature check for PDF (`%PDF`) and for the raster image formats; every other format
     *   is decided from [location] alone.
     * @return the detected [DocumentFormat], or [DocumentFormat.UNKNOWN] when none of the checks match.
     */
    fun detect(location: DocumentLocation, bytes: ByteArray?): DocumentFormat {
        val name = location.displayName.lowercase()
        val mimeType = location.mimeType?.lowercase()
        val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        return when {
            mimeType == "text/plain" || name.endsWith(".txt") -> DocumentFormat.TXT
            mimeType == "application/pdf" || name.endsWith(".pdf") || (bytes?.startsWithAscii("%PDF") == true) -> DocumentFormat.PDF
            mimeType == "application/epub" || mimeType == "application/epub+zip" || name.endsWith(".epub") -> DocumentFormat.EPUB
            mimeType == "application/vnd.comicbook+zip" || mimeType == "application/x-cbz" || extension == "cbz" ->
                DocumentFormat.CBZ
            mimeType in SupportedImageMimeTypes || extension in SupportedImageExtensions || (bytes?.hasRasterImageSignature() == true) ->
                DocumentFormat.IMAGE
            else -> DocumentFormat.UNKNOWN
        }
    }
}

/**
 * The image MIME types this reader actually supports, i.e. [SupportedDocumentMimeTypes] narrowed to
 * the `image/` family.
 */
private val SupportedImageMimeTypes = SupportedDocumentMimeTypes.filterTo(hashSetOf()) { it.startsWith("image/") }

/**
 * The raster file extensions this reader can decode; [SupportedDocumentExtensions] narrowed the same way.
 */
private val SupportedImageExtensions = SupportedDocumentExtensions.filterTo(hashSetOf()) { extension ->
    extension in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
}

/**
 * Whether the byte at each position of [value] appears, in order, starting at index 0 — an ASCII marker
 * check (`%PDF`, `GIF89a`, `RIFF`, `BM`, …) that never needs [value] itself decoded from bytes.
 *
 * @receiver the file's leading bytes; too short a receiver is treated as not matching rather than
 *   throwing.
 * @param value the ASCII marker expected at the start of the receiver.
 */
private fun ByteArray.startsWithAscii(value: String): Boolean =
    size >= value.length && value.indices.all { index -> this[index].toInt() and 0xFF == value[index].code }

/**
 * Whether [this] opens with the magic bytes of a JPEG, PNG, GIF, WebP, or BMP — the formats
 * [DocumentFormatDetector.detect] treats as IMAGE. BMP's own signature is just the two ASCII bytes
 * `BM`, the shortest of the five and the only one not anchored by a longer marker or a length-prefixed
 * chunk name; a non-BMP file that happens to start with those two bytes would be misread as one, but
 * no format this reader is asked to support collides with it in practice.
 *
 * @receiver the file's leading bytes.
 */
private fun ByteArray.hasRasterImageSignature(): Boolean =
    startsWithBytes(0xFF, 0xD8, 0xFF) ||
        startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
        startsWithAscii("GIF87a") ||
        startsWithAscii("GIF89a") ||
        (startsWithAscii("RIFF") && size >= 12 && copyOfRange(8, 12).startsWithAscii("WEBP")) ||
        startsWithAscii("BM")

/**
 * Whether the byte at each position of [values] appears, in order, starting at index 0 — the raw-byte
 * counterpart of [startsWithAscii], for signatures (JPEG's `FF D8 FF`, PNG's 8-byte header) that are
 * not themselves ASCII text.
 *
 * @receiver the file's leading bytes; too short a receiver is treated as not matching rather than
 *   throwing.
 * @param values the expected byte sequence, each given as an `Int` in `0..255`.
 */
private fun ByteArray.startsWithBytes(vararg values: Int): Boolean =
    size >= values.size && values.indices.all { index -> this[index].toInt() and 0xFF == values[index] }
