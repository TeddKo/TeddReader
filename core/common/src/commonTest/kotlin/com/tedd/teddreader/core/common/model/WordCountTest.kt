package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [String.wordCount]의 단어 수 계약을 고정한다. 모든 연속 공백 문자는 단어를 구분하고, 앞뒤 공백은 무시하며, 결과는 텍스트에서 공백이 아닌 토큰의 수이다. 구현 변경이 관찰 가능한 동작을 유지하도록 경계 사례, 여러 공백이 섞인 구간, Unicode 공백 문자를 다룬다.
 *
 * 기존 구현은 `trim().split(Regex("\\s+"))`을 사용하여 정리 결과 전체의 중간 `String` 할당과 분할 결과 부분 문자열의 `O(n)` 목록이라는 두 비용이 있었다. 대체 구현은 `O(1)` 추가 메모리로 문자를 한 번 순회한다. 이 테스트는 전환에 의미론적 회귀가 없음을 보장한다.
 */
class WordCountTest {

    @Test
    fun emptyStringReturnsZero() {
        assertEquals(0, "".wordCount())
    }

    @Test
    fun blankStringReturnsZero() {
        assertEquals(0, "   ".wordCount())
    }

    @Test
    fun singleWordReturnsOne() {
        assertEquals(1, "hello".wordCount())
    }

    @Test
    fun twoWordsReturnTwo() {
        assertEquals(2, "hello world".wordCount())
    }

    @Test
    fun leadingWhitespaceIsIgnored() {
        assertEquals(1, "   word".wordCount())
    }

    @Test
    fun trailingWhitespaceIsIgnored() {
        assertEquals(1, "word   ".wordCount())
    }

    @Test
    fun leadingAndTrailingWhitespaceIsIgnored() {
        assertEquals(2, "  hello world  ".wordCount())
    }

    @Test
    fun multipleSpacesBetweenWordsCountAsOneSeparator() {
        assertEquals(3, "one   two   three".wordCount())
    }

    @Test
    fun tabsActAsSeparators() {
        assertEquals(3, "one\ttwo\tthree".wordCount())
    }

    @Test
    fun newlinesActAsSeparators() {
        assertEquals(3, "one\ntwo\nthree".wordCount())
    }

    @Test
    fun carriageReturnActsAsSeparator() {
        assertEquals(2, "one\rtwo".wordCount())
    }

    @Test
    fun carriageReturnLinefeedActsAsSingleSeparator() {
        assertEquals(2, "one\r\ntwo".wordCount())
    }

    @Test
    fun formFeedActsAsSeparator() {
        assertEquals(2, "one\u000Ctwo".wordCount())
    }

    @Test
    fun verticalTabActsAsSeparator() {
        assertEquals(2, "one\u000Btwo".wordCount())
    }

    @Test
    fun mixedWhitespaceRunsCountAsOneSeparator() {
        assertEquals(2, "one \t \n \r two".wordCount())
    }

    @Test
    fun onlyNewlinesWithWordsOnSeparateLines() {
        assertEquals(4, "first\nsecond\nthird\nfourth".wordCount())
    }

    @Test
    fun tabSeparatedContent() {
        assertEquals(5, "a\tb\tc\td\te".wordCount())
    }

    @Test
    fun unicodeNonBreakingSpaceActsAsSeparator() {
        assertEquals(2, "hello\u00A0world".wordCount())
    }

    @Test
    fun ideographicSpaceActsAsSeparator() {
        assertEquals(2, "hello\u3000world".wordCount())
    }

    @Test
    fun enSpaceActsAsSeparator() {
        assertEquals(2, "hello\u2002world".wordCount())
    }

    @Test
    fun emSpaceActsAsSeparator() {
        assertEquals(2, "hello\u2003world".wordCount())
    }

    @Test
    fun multipleUnicodeWhitespaceCharactersBetweenWords() {
        assertEquals(2, "hello\u00A0\u3000\u2003world".wordCount())
    }

    @Test
    fun leadingAndTrailingUnicodeWhitespace() {
        assertEquals(1, "\u00A0\u3000word\u2003\u00A0".wordCount())
    }

    @Test
    fun singleCharacterWordReturnsOne() {
        assertEquals(1, "a".wordCount())
    }

    @Test
    fun multipleOneCharacterWords() {
        assertEquals(3, "a b c".wordCount())
    }

    @Test
    fun longTextWithManyWords() {
        val text = (1..100).joinToString(" ") { "word$it" }
        assertEquals(100, text.wordCount())
    }

    @Test
    fun textContainingOnlyUnicodeWhitespaceReturnsZero() {
        assertEquals(0, "\u00A0\u2003\u3000".wordCount())
    }

    @Test
    fun mixedAsciiAndUnicodeWhitespaceSeparatorsInOneRun() {
        assertEquals(2, "alpha \t\u00A0\n\u3000 beta".wordCount())
    }

    @Test
    fun punctuationAttachedToWordIsPartOfWord() {
        assertEquals(3, "hello, world! test.".wordCount())
    }

    @Test
    fun hyphenatedWordCountsAsOneWord() {
        assertEquals(1, "well-known".wordCount())
    }

    @Test
    fun koreanTextSeparatedBySpaces() {
        assertEquals(3, "안녕 하세요 세계".wordCount())
    }

    @Test
    fun emojiSeparatedBySpaces() {
        assertEquals(3, "🙂 🎉 🚀".wordCount())
    }
}
