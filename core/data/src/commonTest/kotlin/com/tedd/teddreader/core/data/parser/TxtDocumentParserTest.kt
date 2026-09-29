package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [TxtDocumentParser]의 단일 섹션 계약을 고정한다. CRLF에서 LF로의 줄바꿈 정규화와 순수 텍스트
 * 문서가 보고하는 파생 문자·단어 수를 포함한다.
 */
class TxtDocumentParserTest {
    /** 모든 테스트가 공유하는 상태 없는 일반 텍스트 파서. */
    private val parser = TxtDocumentParser()

    /** `.txt` 파일은 줄바꿈이 정규화된 텍스트 전체와 파생 수치를 가진 섹션 하나가 된다. */
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
 * [TxtTextDecoder]의 디코딩을 실제 TXT 입력 인코딩과 RFC 3629 UTF-8 경계에 걸쳐 고정한다.
 */
class TxtTextDecoderTest {
    /** UTF-8 바이트 순서 표시는 제거되고 나머지는 UTF-8로 디코딩된다. */
    @Test
    fun decodesUtf8BomText() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "가나다".encodeToByteArray()

        assertEquals("가나다", TxtTextDecoder.decode(bytes))
    }

    /** UTF-16 리틀엔디안 바이트 순서 표시가 제거되고 나머지는 UTF-16LE로 디코딩된다. */
    @Test
    fun decodesUtf16LittleEndianBomText() {
        val bytes = byteArrayOf(
            0xFF.toByte(),
            0xFE.toByte(),
            0x00,
            0xAC.toByte(),
            0x98.toByte(),
            0xB0.toByte(),
        )

        assertEquals("가나", TxtTextDecoder.decode(bytes))
    }

    /** 바이트 순서 표시가 없어도 읽기 점수가 가장 높은 UTF-16LE 후보가 선택된다. */
    @Test
    fun decodesUtf16LittleEndianTextWithoutBom() {
        val bytes = byteArrayOf(
            0x00,
            0xAC.toByte(),
            0x98.toByte(),
            0xB0.toByte(),
        )

        assertEquals("가나", TxtTextDecoder.decode(bytes))
    }

    /** 바이트 순서 표시가 없는 MS949·CP949 한국어 바이트도 플랫폼 디코더 후보로 복원된다. */
    @Test
    fun decodesMs949KoreanText() {
        val bytes = byteArrayOf(
            0xBE.toByte(),
            0xC8.toByte(),
            0xB3.toByte(),
            0xE7.toByte(),
            0xC7.toByte(),
            0xCF.toByte(),
            0xBC.toByte(),
            0xBC.toByte(),
            0xBF.toByte(),
            0xE4.toByte(),
        )

        assertEquals("안녕하세요", TxtTextDecoder.decode(bytes))
    }

    /** ASCII만 있는 바이트도 이어지는 바이트 없이 완전한 UTF-8로 인정된다. */
    @Test
    fun acceptsAsciiUtf8() {
        assertEquals(true, "plain text".encodeToByteArray().isValidUtf8())
    }

    /** 최소형이 아닌 3바이트 시퀀스는 UTF-8로 인정되지 않는다. */
    @Test
    fun rejectsOverlongUtf8Sequence() {
        assertEquals(
            false,
            byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte()).isValidUtf8(),
        )
    }

    /** UTF-16 surrogate 영역을 표현하는 UTF-8 시퀀스는 인정되지 않는다. */
    @Test
    fun rejectsUtf8SurrogateCodePoint() {
        assertEquals(
            false,
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()).isValidUtf8(),
        )
    }

    /** `U+10FFFF`보다 큰 코드 포인트를 표현하는 UTF-8 시퀀스는 인정되지 않는다. */
    @Test
    fun rejectsUtf8CodePointAboveUnicodeMaximum() {
        assertEquals(
            false,
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()).isValidUtf8(),
        )
    }

    /** RFC 3629의 양 끝 경계에 있는 유효한 4바이트 시퀀스는 계속 인정된다. */
    @Test
    fun acceptsValidFourByteUtf8Boundaries() {
        assertEquals(
            true,
            byteArrayOf(0xF0.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()).isValidUtf8(),
        )
        assertEquals(
            true,
            byteArrayOf(0xF4.toByte(), 0x8F.toByte(), 0xBF.toByte(), 0xBF.toByte()).isValidUtf8(),
        )
    }
}
