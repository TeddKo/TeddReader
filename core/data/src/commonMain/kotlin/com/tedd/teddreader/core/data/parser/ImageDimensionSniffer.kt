package com.tedd.teddreader.core.data.parser

/**
 * Reads the true pixel width/height straight out of an image's own binary header, the same way any
 * real image decoder would, rather than guessing an aspect ratio. Supports the formats EPUB packages
 * actually ship: PNG, JPEG, GIF, WebP, BMP and SVG. Returns null for anything else or a
 * truncated/corrupt file.
 */
internal fun sniffImageDimensions(bytes: ByteArray): Pair<Int, Int>? =
    sniffPng(bytes) ?: sniffGif(bytes) ?: sniffWebp(bytes) ?: sniffBmp(bytes) ?: sniffJpeg(bytes)
        ?: sniffSvg(bytes)

/**
 * BMP's width/height, from the 40-byte BITMAPINFOHEADER that follows the 2-byte `BM` signature.
 *
 * The header's height field is signed: a negative value does not mean a negative size, only that the
 * image's rows are stored top-down rather than the usual bottom-up, so the sign is discarded here and
 * the magnitude used as the real height.
 */
private fun sniffBmp(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 26 || bytes[0] != 0x42.toByte() || bytes[1] != 0x4D.toByte()) return null
    val width = bytes.readInt32LE(18)
    val height = bytes.readInt32LE(22)
    return dimensionsOrNull(width, if (height < 0) -height else height)
}

/**
 * Size of an SVG, which EPUBs use for plates and covers far more often than any raster format — a book
 * that wraps its illustrations this way had no measurable picture at all, so every one of them claimed
 * a whole page. `viewBox` is read first because that is what actually sets the proportions; a
 * percentage width states nothing about them and is skipped.
 */
private fun sniffSvg(bytes: ByteArray): Pair<Int, Int>? {
    val header = bytes.decodeToString(endIndex = bytes.size.coerceAtMost(SvgHeaderChars))
    val openTag = SvgOpenTagRegex.find(header)?.value ?: return null

    SvgViewBoxRegex.find(openTag)?.groupValues?.get(1)?.let { viewBox ->
        val numbers = viewBox.trim().split(SvgSeparatorRegex).mapNotNull(String::toFloatOrNull)
        if (numbers.size >= 4 && numbers[2] > 0f && numbers[3] > 0f) {
            return roundedDimensions(numbers[2], numbers[3])
        }
    }

    val width = SvgWidthRegex.find(openTag)?.groupValues?.get(1)?.toSvgLength()
    val height = SvgHeightRegex.find(openTag)?.groupValues?.get(1)?.toSvgLength()
    if (width != null && height != null) return roundedDimensions(width, height)
    return null
}

/** An SVG length in a unit that still resolves to a fixed size; a percentage does not. */
private fun String.toSvgLength(): Float? {
    val value = trim().removeSuffix("px").trim()
    if (value.isEmpty() || value.endsWith("%")) return null
    return value.toFloatOrNull()?.takeIf { it > 0f }
}

/**
 * [width]/[height] rounded to the nearest whole pixel and floored at 1, so a very thin declared
 * dimension — a hairline rule's SVG can legitimately say `viewBox="0 0 640 0.5"` — never rounds down to
 * a zero-sized image that [dimensionsOrNull] would then reject as unmeasured.
 */
private fun roundedDimensions(width: Float, height: Float): Pair<Int, Int>? = dimensionsOrNull(
    (width + 0.5f).toInt().coerceAtLeast(1),
    (height + 0.5f).toInt().coerceAtLeast(1),
)

/**
 * How much of the file's start [sniffSvg] scans for the opening `<svg>` tag — generous enough for any
 * realistic run of namespace declarations plus a `viewBox`/`width`/`height`, without decoding (or even
 * fully reading) the rest of the file just to size it.
 */
private const val SvgHeaderChars = 4096

/** Matches the SVG root element's opening tag, attributes and all, within the scanned header. */
private val SvgOpenTagRegex = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * Captures a `viewBox` attribute's raw value — checked first, because it is what actually fixes an SVG's
 * proportions.
 */
private val SvgViewBoxRegex = Regex("""viewBox\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** Captures a `width` attribute's raw value, consulted only when no usable `viewBox` was found. */
private val SvgWidthRegex = Regex("""\bwidth\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** Captures a `height` attribute's raw value, consulted only when no usable `viewBox` was found. */
private val SvgHeightRegex = Regex("""\bheight\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** Splits a `viewBox` value on whichever of comma or whitespace it uses to separate its four numbers. */
private val SvgSeparatorRegex = Regex("""[\s,]+""")

/**
 * PNG's width/height, from the mandatory IHDR chunk's own big-endian fields.
 *
 * PNG requires IHDR to be the very first chunk, sitting immediately after the 8-byte signature, so its
 * width and height can be read at a fixed offset without walking the chunk list at all.
 */
private fun sniffPng(bytes: ByteArray): Pair<Int, Int>? {
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    if (bytes.size < 24 || !bytes.regionMatches(0, signature)) return null
    if (!bytes.regionMatches(12, "IHDR".encodeToByteArray())) return null
    val width = bytes.readInt32BE(16)
    val height = bytes.readInt32BE(20)
    return dimensionsOrNull(width, height)
}

/**
 * GIF's width/height, read little-endian from the fixed-offset logical screen descriptor that follows
 * the `GIF87a`/`GIF89a` signature.
 */
private fun sniffGif(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 10) return null
    val isGif = bytes.regionMatches(0, "GIF87a".encodeToByteArray()) ||
        bytes.regionMatches(0, "GIF89a".encodeToByteArray())
    if (!isGif) return null
    val width = bytes.readInt16LE(6)
    val height = bytes.readInt16LE(8)
    return dimensionsOrNull(width, height)
}

/**
 * WebP's width/height, read from whichever of its three chunk formats the file actually uses.
 *
 * `VP8X` (extended format) stores width-minus-one and height-minus-one as separate little-endian
 * 24-bit fields. `VP8L` (lossless) packs both dimensions, each minus one, into a single little-endian
 * 32-bit value that starts right after a fixed `0x2F` marker byte. Plain `VP8 ` (lossy) carries a
 * 3-byte frame tag followed by the codec's `0x9d 0x01 0x2a` sync code, and only past that sync code do
 * the little-endian width/height fields — each masked to their low 14 bits — appear.
 */
private fun sniffWebp(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) return null
    if (!bytes.regionMatches(0, "RIFF".encodeToByteArray())) return null
    if (!bytes.regionMatches(8, "WEBP".encodeToByteArray())) return null
    return when {
        bytes.regionMatches(12, "VP8X".encodeToByteArray()) -> {
            val width = bytes.readInt24LE(24) + 1
            val height = bytes.readInt24LE(27) + 1
            dimensionsOrNull(width, height)
        }
        bytes.regionMatches(12, "VP8L".encodeToByteArray()) -> {
            if (bytes.size < 25 || bytes[20] != 0x2F.toByte()) return null
            val bits = bytes.readInt32LE(21)
            val width = (bits and 0x3FFF) + 1
            val height = ((bits shr 14) and 0x3FFF) + 1
            dimensionsOrNull(width, height)
        }
        bytes.regionMatches(12, "VP8 ".encodeToByteArray()) -> {
            if (bytes.size < 30) return null
            if (bytes[23] != 0x9d.toByte() || bytes[24] != 0x01.toByte() || bytes[25] != 0x2a.toByte()) return null
            val width = bytes.readInt16LE(26) and 0x3FFF
            val height = bytes.readInt16LE(28) and 0x3FFF
            dimensionsOrNull(width, height)
        }
        else -> null
    }
}

/**
 * JPEG's width/height, found by walking marker segments after the SOI (`FF D8`) until a
 * start-of-frame marker turns up.
 *
 * Unlike PNG/GIF/WebP, JPEG does not keep its dimensions at a fixed offset — an embedded EXIF thumbnail
 * or other `APPn` segment can sit ahead of them — so the segments have to be walked one at a time.
 * `0xFF` bytes used purely as fill between markers are skipped (the `marker == 0xFF` branch), and
 * markers with no payload segment — SOI/EOI and the `RSTn` restart markers `0xD0`..`0xD7` — are skipped
 * without reading a length. Once a segment length is read, height then width sit right after the SOF
 * segment's one-byte sample precision. Markers `0xC4`, `0xC8` and `0xCC` (DHT, the reserved JPG
 * extension, and DAC) fall inside the `0xC0`..`0xCF` range an SOF marker also occupies but are not one,
 * so they are excluded explicitly rather than misread as a frame header. Reaching SOS (`0xFFDA`, where
 * the entropy-coded scan data begins) before any SOF has been found means this file has none to report,
 * so this returns null there instead of continuing into compressed data that is not made of markers at
 * all.
 */
private fun sniffJpeg(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    var index = 2
    while (index + 3 < bytes.size) {
        if (bytes[index] != 0xFF.toByte()) {
            index += 1
            continue
        }
        val marker = bytes[index + 1].toInt() and 0xFF
        if (marker == 0xFF) {
            index += 1
            continue
        }
        if (marker == 0xD8 || marker == 0xD9 || (marker in 0xD0..0xD7)) {
            index += 2
            continue
        }
        val segmentLength = bytes.readInt16BE(index + 2)
        if (segmentLength < 2) return null
        val isSofMarker = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isSofMarker) {
            val precisionOffset = index + 4
            if (precisionOffset + 4 >= bytes.size) return null
            val height = bytes.readInt16BE(precisionOffset + 1)
            val width = bytes.readInt16BE(precisionOffset + 3)
            return dimensionsOrNull(width, height)
        }
        if (marker == 0xDA) return null
        index += 2 + segmentLength
    }
    return null
}

/**
 * [width] and [height] as a result, or null if either is not a real, positive size.
 *
 * The shared guard every format-specific sniffer above returns through: a header that decodes to a
 * zero or negative dimension is corrupt or unsupported, and is reported the same way as a signature
 * that never matched at all, rather than handed to a caller as a bogus size.
 */
private fun dimensionsOrNull(width: Int, height: Int): Pair<Int, Int>? =
    if (width > 0 && height > 0) width to height else null

/**
 * Whether [other] appears byte-for-byte starting at [offset] in [this], without allocating a
 * sub-array just to compare it.
 *
 * @receiver the buffer being searched.
 * @param offset position in the receiver where [other] must start; a negative offset or a receiver too
 *   short to hold [other] there is treated as no match rather than throwing.
 * @param other the bytes that must appear at [offset].
 */
private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
    if (offset < 0 || offset + other.size > size) return false
    for (i in other.indices) if (this[offset + i] != other[i]) return false
    return true
}

/**
 * The two bytes at [offset], as a big-endian unsigned 16-bit integer — how JPEG stores its segment
 * lengths and its SOF width/height fields.
 *
 * @receiver the buffer being read.
 * @param offset index of the first of the two bytes.
 */
private fun ByteArray.readInt16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

/**
 * The two bytes at [offset], as a little-endian unsigned 16-bit integer — how GIF's logical screen
 * descriptor and the `VP8 ` WebP chunk store their dimensions.
 *
 * @receiver the buffer being read.
 * @param offset index of the first of the two bytes.
 */
private fun ByteArray.readInt16LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/**
 * The three bytes at [offset], as a little-endian unsigned 24-bit integer — how the `VP8X` WebP chunk
 * stores each of its width-minus-one and height-minus-one fields.
 *
 * @receiver the buffer being read.
 * @param offset index of the first of the three bytes.
 */
private fun ByteArray.readInt24LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

/**
 * The four bytes at [offset], as a big-endian 32-bit integer — how PNG's IHDR chunk stores its width
 * and height.
 *
 * @receiver the buffer being read.
 * @param offset index of the first of the four bytes.
 */
private fun ByteArray.readInt32BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/**
 * The four bytes at [offset], as a little-endian 32-bit integer — how BMP's BITMAPINFOHEADER stores its
 * width and (possibly negative) height, and how the `VP8L` WebP chunk packs both of its dimensions into
 * one field.
 *
 * @receiver the buffer being read.
 * @param offset index of the first of the four bytes.
 */
private fun ByteArray.readInt32LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
