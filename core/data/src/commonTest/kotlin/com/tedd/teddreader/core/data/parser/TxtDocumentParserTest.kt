package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class TxtDocumentParserTest {
    private val parser = TxtDocumentParser()

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

class TxtTextDecoderTest {
    @Test
    fun decodesUtf8BomText() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "가나다".encodeToByteArray()

        assertEquals("가나다", TxtTextDecoder.decode(bytes))
    }

    @Test
 fun decodesUtf16LittleEndianBomText() {
 val bytes = byteArrayOf(
 0xFF.toByte(), 0xFE.toByte(),
 0x00, 0xAC.toByte(),
 0x98.toByte(), 0xB0.toByte(),
 )

 assertEquals("가나", TxtTextDecoder.decode(bytes))
 }

 @Test
 fun decodesUtf16LittleEndianTextWithoutBom() {
 val bytes = byteArrayOf(
 0x00, 0xAC.toByte(),
 0x98.toByte(), 0xB0.toByte(),
 )

 assertEquals("가나", TxtTextDecoder.decode(bytes))
 }

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
