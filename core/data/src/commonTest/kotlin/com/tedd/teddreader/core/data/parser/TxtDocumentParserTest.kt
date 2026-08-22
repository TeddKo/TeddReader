package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [TxtDocumentParser]'s single-section contract, including CRLF-to-LF line-ending normalization
 * and the derived character/word counts a plain-text document reports.
 */
class TxtDocumentParserTest {
    private val parser = TxtDocumentParser()

    /**
     * A `.txt` file becomes one section holding its whole (line-ending-normalized) text, with the character
     * and word counts it implies.
     */
    @Test
    fun parsesTextAsSingleSectionReaderDocument() {
        val document = parser.parse(
            id = DocumentId("txt-1"),
            title = "Sample",
            text = "Hello reader\r\n서비스",
        )

        assertEquals(DocumentFormat.TXT, document.format)
        assertEquals("Sample", document.title)
        assertEquals(1, document.sections.size)
        assertEquals("Hello reader\n서비스", document.sections.single().text)
        assertEquals(16L, document.characterCount)
        assertEquals(3L, document.wordCount)
    }
}

/**
 * Pins [TxtTextDecoder]'s byte-to-text decoding across the encodings a plain-text file actually
 * arrives in: UTF-8 with a byte-order mark, UTF-16LE with and without one, and legacy Korean
 * MS949/CP949.
 */
class TxtTextDecoderTest {
    /** A UTF-8 byte-order mark is recognized and stripped, decoding the rest as UTF-8. */
    @Test
    fun decodesUtf8BomText() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "가나다".encodeToByteArray()

        assertEquals("가나다", TxtTextDecoder.decode(bytes))
    }

    /** A UTF-16 little-endian byte-order mark is recognized, decoding the rest as UTF-16LE. */
    @Test
 fun decodesUtf16LittleEndianBomText() {
 val bytes = byteArrayOf(
 0xFF.toByte(), 0xFE.toByte(),
 0x00, 0xAC.toByte(),
 0x98.toByte(), 0xB0.toByte(),
 )

 assertEquals("가나", TxtTextDecoder.decode(bytes))
 }

 /**
  * With no byte-order mark at all, raw bytes are still detected and decoded as UTF-16LE, rather than
  * defaulting to UTF-8 and producing mojibake.
  */
 @Test
 fun decodesUtf16LittleEndianTextWithoutBom() {
 val bytes = byteArrayOf(
 0x00, 0xAC.toByte(),
 0x98.toByte(), 0xB0.toByte(),
 )

 assertEquals("가나", TxtTextDecoder.decode(bytes))
 }

 /**
  * Legacy Korean MS949/CP949-encoded bytes, with no byte-order mark to signal any Unicode encoding, are
  * still decoded correctly.
  */
 @Test
 fun decodesMs949KoreanText() {
 val bytes = byteArrayOf(
 0xBE.toByte(), 0xC8.toByte(),
 0xB3.toByte(), 0xE7.toByte(),
 0xC7.toByte(), 0xCF.toByte(),
 0xBC.toByte(), 0xBC.toByte(),
 0xBF.toByte(), 0xE4.toByte(),
 )

 assertEquals("안녕하세요", TxtTextDecoder.decode(bytes))
 }
}
