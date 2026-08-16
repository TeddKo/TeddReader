package com.tedd.teddreader.core.data.parser

/**
 * Reads the true pixel width/height straight out of an image's own binary header, the same way any
 * real image decoder would, rather than guessing an aspect ratio. Supports the formats EPUB packages
 * actually ship: PNG, JPEG, GIF and WebP. Returns null for anything else or a truncated/corrupt file.
 */
internal fun sniffImageDimensions(bytes: ByteArray): Pair<Int, Int>? =
    sniffPng(bytes) ?: sniffGif(bytes) ?: sniffWebp(bytes) ?: sniffJpeg(bytes)

private fun sniffPng(bytes: ByteArray): Pair<Int, Int>? {
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    if (bytes.size < 24 || !bytes.regionMatches(0, signature)) return null
    // The IHDR chunk is required to be the first chunk, immediately after the signature.
    if (!bytes.regionMatches(12, "IHDR".encodeToByteArray())) return null
    val width = bytes.readInt32BE(16)
    val height = bytes.readInt32BE(20)
    return dimensionsOrNull(width, height)
}

private fun sniffGif(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 10) return null
    val isGif = bytes.regionMatches(0, "GIF87a".encodeToByteArray()) ||
        bytes.regionMatches(0, "GIF89a".encodeToByteArray())
    if (!isGif) return null
    val width = bytes.readInt16LE(6)
    val height = bytes.readInt16LE(8)
    return dimensionsOrNull(width, height)
}

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
            // Frame tag (3 bytes) then the sync code 0x9d 0x01 0x2a.
            if (bytes[23] != 0x9d.toByte() || bytes[24] != 0x01.toByte() || bytes[25] != 0x2a.toByte()) return null
            val width = bytes.readInt16LE(26) and 0x3FFF
            val height = bytes.readInt16LE(28) and 0x3FFF
            dimensionsOrNull(width, height)
        }
        else -> null
    }
}

private fun sniffJpeg(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    var index = 2
    while (index + 3 < bytes.size) {
        if (bytes[index] != 0xFF.toByte()) {
            index += 1
            continue
        }
        val marker = bytes[index + 1].toInt() and 0xFF
        // Padding fill bytes between markers.
        if (marker == 0xFF) {
            index += 1
            continue
        }
        // Markers with no payload segment.
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
        if (marker == 0xDA) return null // Start of scan: no SOF found before the compressed data.
        index += 2 + segmentLength
    }
    return null
}

private fun dimensionsOrNull(width: Int, height: Int): Pair<Int, Int>? =
    if (width > 0 && height > 0) width to height else null

private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
    if (offset < 0 || offset + other.size > size) return false
    for (i in other.indices) if (this[offset + i] != other[i]) return false
    return true
}

private fun ByteArray.readInt16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.readInt16LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.readInt24LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

private fun ByteArray.readInt32BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readInt32LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
