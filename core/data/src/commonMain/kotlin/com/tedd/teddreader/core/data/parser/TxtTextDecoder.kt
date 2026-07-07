package com.tedd.teddreader.core.data.parser

object TxtTextDecoder {
    fun decode(bytes: ByteArray): String {
        decodeBom(bytes)?.let { return it }
        if (bytes.isValidUtf8()) return bytes.decodeToString()

        return listOfNotNull(
            decodeLegacyKoreanText(bytes)?.takeIfReadable(),
            bytes.decodeUtf16LittleEndian(startIndex = 0).takeIfReadable(),
            bytes.decodeUtf16BigEndian(startIndex = 0).takeIfReadable(),
        ).maxByOrNull { it.readableScore() } ?: bytes.decodeToString()
    }

    private fun decodeBom(bytes: ByteArray): String? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> bytes.copyOfRange(3, bytes.size).decodeToString()
        bytes.startsWith(0xFF, 0xFE) -> bytes.decodeUtf16LittleEndian(startIndex = 2)
        bytes.startsWith(0xFE, 0xFF) -> bytes.decodeUtf16BigEndian(startIndex = 2)
        else -> null
    }
}

internal expect fun decodeLegacyKoreanText(bytes: ByteArray): String?

private fun String.takeIfReadable(): String? = takeIf { it.readableScore() > 0 }

private fun String.readableScore(): Int = sumOf { char ->
    when {
        char == '\uFFFD' || char == '\u0000' -> -100
        char == '\n' || char == '\r' || char == '\t' -> 1
        char in '\uAC00'..'\uD7A3' -> 5
        char in ' '..'~' -> 2
        char.isISOControl() -> -20
        else -> 0
    }
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }

private fun ByteArray.decodeUtf16LittleEndian(startIndex: Int): String = buildString {
    var index = startIndex
    while (index + 1 < size) {
        append(
            ((this@decodeUtf16LittleEndian[index].toInt() and 0xFF) or
                ((this@decodeUtf16LittleEndian[index + 1].toInt() and 0xFF) shl 8)).toChar(),
        )
        index += 2
    }
}

private fun ByteArray.decodeUtf16BigEndian(startIndex: Int): String = buildString {
    var index = startIndex
    while (index + 1 < size) {
        append(
            (((this@decodeUtf16BigEndian[index].toInt() and 0xFF) shl 8) or
                (this@decodeUtf16BigEndian[index + 1].toInt() and 0xFF)).toChar(),
        )
        index += 2
    }
}

private fun ByteArray.isValidUtf8(): Boolean {
    var index = 0
    while (index < size) {
        val first = this[index].toInt() and 0xFF
        val needed = when {
            first <= 0x7F -> 0
            first in 0xC2..0xDF -> 1
            first in 0xE0..0xEF -> 2
            first in 0xF0..0xF4 -> 3
            else -> return false
        }
        if (index + needed >= size) return false
        repeat(needed) { offset ->
            val next = this[index + offset + 1].toInt() and 0xFF
            if (next !in 0x80..0xBF) return false
        }
        index += needed + 1
    }
    return true
}
