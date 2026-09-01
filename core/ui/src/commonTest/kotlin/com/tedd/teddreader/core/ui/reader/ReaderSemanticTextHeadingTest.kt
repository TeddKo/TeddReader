package com.tedd.teddreader.core.ui.reader

import androidx.compose.ui.text.font.FontWeight
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 제목이 문서가 요구하는 대로 설정되고 페이지로 가는 과정에서 아무것도 더해지지 않음을 고정한다.
 *
 * 리딩 시스템은 제목을 활자로 그린다 — 더 굵고, 더 크고, 산문에서 떨어져 — 이며 자기 문자를 쓰지
 * 않는다. 예전에는 렌더러가 막대 글리프를 접두로 붙였는데, 이는 책이 결코 요구하지 않은 장식을
 * 모든 제목 앞에 두고 그 뒤의 모든 렌더링된 오프셋을 밀어냈다; 이 케이스들은 그 두 문제가 다시
 * 돌아오지 않게 지킨다.
 */
class ReaderSemanticTextHeadingTest {
    /** 제목 뒤에 산문이 오는, 여기 모든 케이스가 렌더링하는 형태. */
    private val text = "Chapter One\n\nBody text follows."

    /** "Chapter One"이 끝나는 지점으로, 제목 블록이 그 제목만 덮고 그 이상은 덮지 않게 한다. */
    private val headingEnd = 11L

    /**
     * @param level 렌더링할 제목 레벨.
     * @return 아무것도 줄바꿈되지 않을 만큼 넓은 텍스트 컬럼으로 그 레벨에 대해 렌더링된 페이지.
     */
    private fun semanticFor(level: Int) = buildReaderSemanticText(
        text = text,
        blocks = listOf(
            ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, headingEnd), level = level),
            ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(13, text.length.toLong())),
        ),
        lineWidthEm = 20f,
    )

    /** 제목은 자기 자신의 단어만 렌더링할 뿐 그 밖의 아무것도 아니다 — 막대도, 불릿도, 리더 자체의 마커도 없다. */
    @Test
    fun everyHeadingLevelRendersOnlyTheDocumentsOwnWords() {
        (1..6).forEach { level ->
            val rendered = semanticFor(level).annotatedString.text
            assertTrue(
                rendered.startsWith("Chapter One"),
                "level $level had something prefixed to it, got: ${rendered.take(16)}",
            )
        }
    }

    /** 제목을 구별하는 것은 그 활자다: 굵고, 레벨이 얕을수록 더 크다. */
    @Test
    fun aHeadingIsSetApartByItsTypeRatherThanByAMarker() {
        val levelOne = semanticFor(1).annotatedString.spanStyles.first { it.start == 0 }
        val levelThree = semanticFor(3).annotatedString.spanStyles.first { it.start == 0 }

        assertEquals(FontWeight.Bold, levelOne.item.fontWeight)
        assertTrue(
            levelOne.item.fontSize.value > levelThree.item.fontSize.value,
            "a level 1 heading should be set larger than a level 3, got ${levelOne.item.fontSize} vs ${levelThree.item.fontSize}",
        )
    }

    /**
     * 렌더링된 모든 문자는 그것이 유래한 오프셋으로 되돌아 매핑되어, 검색 결과, 북마크, 읽기 위치가
     * 모두 책이 실제로 가지고 있는 텍스트를 계속 가리키게 한다.
     */
    @Test
    fun renderedOffsetsStillAddressTheDocumentsOwnText() {
        val semantic = semanticFor(level = 1)

        assertEquals(0, semantic.sourceOffsetFor(0))
        assertEquals(
            text.indexOf("Body"),
            semantic.sourceOffsetFor(semantic.annotatedString.text.indexOf("Body")),
        )
    }

    /** 제목은 인라인 박스를 예약하지 않는다 — 그림과 룰만 그렇게 하며, 엉뚱한 박스는 한 줄을 차지할 것이다. */
    @Test
    fun aHeadingAddsNoPlaceholderOfItsOwn() {
        assertEquals(emptyList(), semanticFor(level = 1).placeholders)
    }

    /** 잘못된 문서가 가질 수 있는 빈 제목 블록은 자기 것을 아무것도 렌더링하지 않는다. */
    @Test
    fun aHeadingWithNoTextRendersNothing() {
        val semantic = buildReaderSemanticText(
            text = "Body only.",
            blocks = listOf(ReaderBlock(ReaderBlockKind.HEADING, TextRange(0, 0), level = 1)),
            lineWidthEm = 20f,
        )

        assertEquals("Body only.", semantic.annotatedString.text)
    }
}
