package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the word-counting contract of [String.wordCount]: any run of whitespace characters separates
 * words, leading/trailing whitespace is ignored, and the result is the number of non-blank tokens
 * in the text. These tests cover boundary cases, mixed-whitespace runs, and Unicode whitespace
 * characters so that any implementation change is constrained to preserve observable behaviour.
 *
 * The original implementation used `trim().split(Regex("\\s+"))` which carried two costs: a full
 * intermediate `String` allocation for the trim, and an `O(n)` list of substrings for the split.
 * The replacement scans characters in a single pass with `O(1)` extra memory — these tests ensure
 * the transition introduces no semantic regression.
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
