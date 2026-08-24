package com.tedd.teddreader.core.data.parser

/**
 * Decodes a `.txt` file's raw bytes into text when the file carries no declared encoding of its own —
 * the normal case for a plain-text book. Most files this reader opens are either UTF-8 or, for older
 * Korean text files predating UTF-8 adoption, a legacy Korean code page (Windows-949/CP949, a
 * superset of EUC-KR) or plain UTF-16, so [decode] tries encodings in that order of likelihood rather
 * than assuming UTF-8 and letting a whole book render as replacement characters when it isn't.
 */
object TxtTextDecoder {
    /**
     * Decodes [bytes] into text, trying encodings from most to least certain and keeping whichever
     * result actually looks like text.
     *
     * The order is: a byte-order mark, if present, settles the question outright ([decodeBom]).
     * Otherwise, bytes that are already valid UTF-8 are decoded as UTF-8 directly — a fast, certain
     * path taken before anything is guessed. Failing both, three candidates are decoded in parallel —
     * legacy Korean (via the platform-specific [decodeLegacyKoreanText]), UTF-16 little-endian, and
     * UTF-16 big-endian — and scored by [readableScore]; the highest-scoring candidate with a positive
     * score wins. Choosing wrong here is not a cosmetic issue: it is the difference between a Korean
     * novel opening as readable prose and opening as a wall of replacement boxes or mojibake for the
     * entire book, since nothing downstream re-guesses once this function has returned.
     *
     * @param bytes The file's raw contents.
     * @return The decoded text. If every candidate scores zero or negative (nothing looked readable),
     *   falls back to lossy UTF-8 decoding, which replaces undecodable byte sequences with `U+FFFD`
     *   rather than throwing — this function never throws.
     */
    fun decode(bytes: ByteArray): String {
        decodeBom(bytes)?.let { return it }
        if (bytes.isValidUtf8()) return bytes.decodeToString()

        return listOfNotNull(
            decodeLegacyKoreanText(bytes)?.takeIfReadable(),
            bytes.decodeUtf16LittleEndian(startIndex = 0).takeIfReadable(),
            bytes.decodeUtf16BigEndian(startIndex = 0).takeIfReadable(),
        ).maxByOrNull { it.readableScore() } ?: bytes.decodeToString()
    }

    /**
     * Decodes [bytes] using whichever encoding its leading byte-order mark declares, stripping the
     * mark itself from the result.
     *
     * @param bytes The file's raw contents.
     * @return The decoded text, or `null` if [bytes] starts with none of the UTF-8, UTF-16LE, or
     *   UTF-16BE byte-order marks — meaning the encoding is not declared and must be guessed instead.
     */
    private fun decodeBom(bytes: ByteArray): String? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> bytes.copyOfRange(3, bytes.size).decodeToString()
        bytes.startsWith(0xFF, 0xFE) -> bytes.decodeUtf16LittleEndian(startIndex = 2)
        bytes.startsWith(0xFE, 0xFF) -> bytes.decodeUtf16BigEndian(startIndex = 2)
        else -> null
    }
}

/**
 * Decodes [bytes] as legacy Korean text (the Windows-949/CP949 code page family), the encoding most
 * pre-UTF-8 Korean `.txt` files were saved in. Each platform reaches this code page through whatever
 * native API it has for it, rather than a shared Kotlin implementation, since neither the JVM nor
 * Kotlin/Native ships one directly usable from common code.
 *
 * @param bytes The file's raw contents.
 * @return The decoded text, or `null` if the platform's decoder cannot map [bytes] under this
 *   encoding — never throws; a failure to decode is reported as `null`, not an exception, so [decode]
 *   can treat it as just another candidate that didn't pan out.
 */
internal expect fun decodeLegacyKoreanText(bytes: ByteArray): String?

/**
 * Filters out a decoded candidate that does not look like real text before it competes with the
 * others on [readableScore] alone, so a candidate dominated by replacement characters or control bytes
 * never wins for having a "less negative" score than another equally-wrong one.
 *
 * @receiver A candidate decoding of the same underlying bytes.
 * @return This string, or `null` if its [readableScore] is zero or negative.
 */
private fun String.takeIfReadable(): String? = takeIf { it.readableScore() > 0 }

/**
 * Heuristic score of how much this string looks like real, readable text rather than the product of
 * decoding bytes under the wrong encoding. [decode] picks the highest-scoring candidate among several
 * guesses, so this score is what actually decides which encoding a whole book is shown in.
 *
 * The weights, per character: `U+FFFD` (the Unicode replacement character) or a NUL byte score -100,
 * since either means the codec could not map that byte at all — a strong signal the encoding is
 * wrong. A newline, carriage return, or tab scores +1, since real prose has line breaks. A Hangul
 * syllable (`U+AC00`..`U+D7A3`) scores +5, the strongest positive signal, since Korean text decoded
 * under the *correct* legacy code page produces a great many of these, while a wrong guess produces
 * none or scatters them nonsensically among garbage. Printable ASCII (space through `~`) scores +2.
 * Any other ISO control character scores -20, since real text essentially never contains one and its
 * presence usually means a multi-byte sequence was split in the wrong place. Everything else scores 0.
 *
 * @receiver A candidate decoding of the same underlying bytes.
 * @return The summed per-character score; higher means more likely to be the correct decoding.
 */
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

/**
 * Whether this byte array starts with [prefix], comparing each byte as unsigned since a raw signed
 * [Byte] comparison against a value like `0xFF` would otherwise never match.
 *
 * @receiver The bytes to check.
 * @param prefix The unsigned byte values (0-255) to match at the start of this array.
 * @return `true` if this array is at least as long as [prefix] and matches it byte for byte.
 */
private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }

/**
 * Decodes this byte array as UTF-16 with little-endian byte order, one `Char` per 2-byte pair.
 *
 * @receiver The bytes to decode.
 * @param startIndex Offset to start decoding from, so a leading byte-order mark can be skipped
 *   without copying the array first.
 * @return The decoded text. A trailing single byte with no pairing byte is silently dropped rather
 *   than causing an error, since this function does no UTF-16 surrogate-pair validation at all.
 */
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

/**
 * Decodes this byte array as UTF-16 with big-endian byte order, one `Char` per 2-byte pair.
 *
 * @receiver The bytes to decode.
 * @param startIndex Offset to start decoding from, so a leading byte-order mark can be skipped
 *   without copying the array first.
 * @return The decoded text. A trailing single byte with no pairing byte is silently dropped rather
 *   than causing an error, since this function does no UTF-16 surrogate-pair validation at all.
 */
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

/**
 * Whether this byte array is valid UTF-8 from start to finish, checked directly against the encoding
 * rules rather than by decoding and looking for replacement characters — [decode] uses this to take a
 * fast, certain UTF-8 path before running the slower multi-candidate guessing chain at all.
 *
 * @receiver The bytes to validate.
 * @return `true` if every leading byte declares a valid sequence length (rejecting the invalid
 *   `0x80..0xC1` and `0xF5..0xFF` leading-byte ranges) and every continuation byte it implies falls in
 *   `0x80..0xBF`, with no sequence left truncated at the end of the array.
 */
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
