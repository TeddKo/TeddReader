package com.tedd.teddreader.core.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 이 타입들은 모두 저장소에 기록하고 이후 실행에서 다시 읽으므로 영속화 형식을 고정한다.
 *
 * 여기서 왕복 변환이 실패하면 업그레이드 때 설정이나 독서 위치를 잃는다. 따라서 페이지 전환 애니메이션 관련 검증은 열거형이 컴파일되는지만 확인하지 않고 모든 페이저 프리셋이 계속 직렬화 가능한 이름인지 확인한다.
 */
class ReaderSerializationTest {
    // 운영 환경에서 다시 읽는 방식과 정확히 같은 기본 Json이다(DocumentRepositoryImpl과 설정
    // 저장소 모두 완화 옵션 없는 `Json`을 사용한다). 여기서 더 느슨한 인스턴스를 쓰면 운영 환경이
    // 거부하는 왕복 변환이 통과하여 알 수 없는 키가 일으키는 조용한 섹션별 블록 손실을 숨긴다.
    private val json = Json

    @Test
    fun readerStyleRoundTripsThroughJson() {
        val style = sepiaReaderStyle().copy(fontSizeSp = 22f, publisherFontKey = "epub-fonts")

        assertEquals(style.copy(publisherFontKey = null), json.decodeFromString(json.encodeToString(style)))
    }

    @Test
    fun publisherThemeModeRoundTripsThroughJson() {
        assertEquals(
            ReaderThemeMode.PUBLISHER,
            json.decodeFromString<ReaderThemeMode>(json.encodeToString(ReaderThemeMode.PUBLISHER)),
        )
    }

    @Test
    fun readerLocationRoundTripsThroughJson() {
        val location: ReaderLocation = ReaderLocation.EpubOffset(spineIndex = 2, offset = 32L)

        assertEquals(location, json.decodeFromString<ReaderLocation>(json.encodeToString(location)))
    }

    @Test
    fun readingHistoryRoundTripsThroughJson() {
        val entry = ReadingHistoryEntry(
            documentId = DocumentId("doc"),
            date = LocalDate(2026, 7, 6),
            activeMillis = 1_000L,
            wordsRead = 120L,
        )

        assertEquals(entry, json.decodeFromString(json.encodeToString(entry)))
    }

    @Test
    fun readerSpanWithInlineStyleDeltaRoundTripsThroughJson() {
        val span = ReaderSpan(
            range = TextRange(3, 7),
            style = null,
            styleDelta = ReaderSpanStyle(
                fontScale = 0.8f,
                italic = true,
                foregroundColor = ReaderColor(0xFF011689),
                fontFamilyName = "KoPub",
                fontHref = "OPS/fonts/KoPub.otf",
                underline = false,
            ),
        )

        assertEquals(span, json.decodeFromString(json.encodeToString(span)))
    }

    @Test
    fun readerBoxStyleRejectsBorderRadiusAbove100Percent() {
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> {
            ReaderBoxStyle(borderRadiusPercent = 101f)
        }

        assertEquals("Border radius percent must be in 0..100.", error.message)
    }

    @Test
    fun readerSpanWithoutCssStyleStillDecodesFromOlderJson() {
        assertEquals(
            ReaderSpan(range = TextRange(3, 7), style = ReaderInlineStyle.BOLD),
            json.decodeFromString("""{"range":{"start":3,"end":7},"style":"BOLD"}"""),
        )
    }

    @Test
    fun readerBlockWithoutPageContainerStillDecodesFromOlderJson() {
        val block = json.decodeFromString<ReaderBlock>(
            """{"kind":"PARAGRAPH","range":{"start":0,"end":4},"level":0,"spans":[],"align":null,"imageHref":null,"label":null,"tableRow":null,"tableColumn":null,"imageAspectRatio":null,"imageNaturalWidthPx":null,"imageWidthPercent":null,"imageWidthEm":null,"float":null,"style":null}""",
        )

        assertFalse(block.isPageContainer)
    }

    @Test
    fun pageAnimationIncludesFoundationPagerPresets() {
        assertEquals(PageAnimation.FLUID_PAGER, json.decodeFromString<PageAnimation>("\"FLUID_PAGER\""))
        assertEquals(PageAnimation.CURL_PAGER, json.decodeFromString<PageAnimation>("\"CURL_PAGER\""))
        assertEquals(PageAnimation.THREE_D_CURL, json.decodeFromString<PageAnimation>("\"THREE_D_CURL\""))
        assertEquals(PageAnimation.CIRCLE_REVEAL, json.decodeFromString<PageAnimation>("\"CIRCLE_REVEAL\""))
        assertEquals(PageAnimation.MOVIE_CAROUSEL, json.decodeFromString<PageAnimation>("\"MOVIE_CAROUSEL\""))
        assertEquals(PageAnimation.PAGE_FLIP, json.decodeFromString<PageAnimation>("\"PAGE_FLIP\""))
    }

}
