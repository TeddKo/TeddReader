package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentFormat
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TxtDocumentParser]의 단일 섹션 계약을 고정한다, CRLF에서 LF로의 줄바꿈 정규화와 순수 텍스트
 * 문서가 보고하는 파생된 문자/단어 수를 포함해서.
 */
class TxtDocumentParserTest {
    private val parser = TxtDocumentParser()

    /**
     * `.txt` 파일은 (줄바꿈이 정규화된) 텍스트 전체를 담은 섹션 하나가 되며, 그것이 암시하는
     * 문자 수와 단어 수를 갖는다.
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
 * [TxtTextDecoder]의 바이트-텍스트 디코딩을, 순수 텍스트 파일이 실제로 도착하는 인코딩들에
 * 걸쳐 고정한다: 바이트 순서 표시가 있는 UTF-8, 있거나 없는 UTF-16LE, 레거시 한국어
 * MS949/CP949.
 */
class TxtTextDecoderTest {
    /** UTF-8 바이트 순서 표시는 인식되어 제거되고, 나머지는 UTF-8로 디코딩된다. */
    @Test
    fun decodesUtf8BomText() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "가나다".encodeToByteArray()

        assertEquals("가나다", TxtTextDecoder.decode(bytes))
    }

    /** UTF-16 리틀엔디안 바이트 순서 표시가 인식되어, 나머지가 UTF-16LE로 디코딩된다. */
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
  * 바이트 순서 표시가 아예 없어도, 원시 바이트는 UTF-8로 기본 처리되어 깨진 글자를 만드는
  * 대신 여전히 UTF-16LE로 감지되어 디코딩된다.
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
  * 유니코드 인코딩을 알릴 바이트 순서 표시가 없는, 레거시 한국어 MS949/CP949로 인코딩된
  * 바이트도 여전히 올바르게 디코딩된다.
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
