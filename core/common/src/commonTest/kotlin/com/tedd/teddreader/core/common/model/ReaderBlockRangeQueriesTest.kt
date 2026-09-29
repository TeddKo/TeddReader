package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 블록 범위 인덱싱이 중첩 구간과 너비 0인 블록을 포함한 기존 멤버십·순서 계약을 유지하는지
 * 검증한다.
 */
class ReaderBlockRangeQueriesTest {
    /** 중첩된 텍스트 범위 안의 독립 블록만 제외하고 결과의 입력 순서를 유지한다. */
    @Test
    fun standaloneBlocksKeepNestedAndZeroWidthContainmentSemantics() {
        val outside = imageAt(30, 30, "outside")
        val nested = imageAt(5, 5, "nested")
        val boundary = imageAt(10, 10, "boundary")
        val blocks = listOf(
            outside,
            ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 10)),
            nested,
            boundary,
            ReaderBlock(ReaderBlockKind.CONTAINER, TextRange(20, 40)),
        )

        assertEquals(listOf(outside), blocks.standaloneBlocks())
    }

    /** 겹치는 중첩 블록과 범위 경계의 너비 0 블록을 기존 입력 순서로 반환한다. */
    @Test
    fun blocksInKeepsNestedAndZeroWidthBoundarySemantics() {
        val spanning = ReaderBlock(ReaderBlockKind.CONTAINER, TextRange(0, 30))
        val before = ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(0, 5))
        val insidePoint = ReaderBlock(ReaderBlockKind.SEPARATOR, TextRange(10, 10))
        val endPoint = ReaderBlock(ReaderBlockKind.SEPARATOR, TextRange(20, 20))
        val nested = ReaderBlock(ReaderBlockKind.PARAGRAPH, TextRange(12, 18))
        val blocks = listOf(endPoint, spanning, before, nested, insidePoint)

        assertEquals(listOf(spanning, nested, insidePoint), blocks.blocksIn(10, 20))
        assertEquals(listOf(endPoint, spanning), blocks.blocksIn(20, 20))
    }

    /**
     * 테스트용 독립 이미지 블록을 만든다.
     *
     * @param start 이미지 범위의 시작 오프셋.
     * @param end 이미지 범위의 끝 오프셋.
     * @param href 이미지 식별에 사용하는 경로.
     * @return 주어진 범위를 갖는 이미지 블록.
     */
    private fun imageAt(start: Long, end: Long, href: String): ReaderBlock = ReaderBlock(
        kind = ReaderBlockKind.IMAGE,
        range = TextRange(start, end),
        imageHref = href,
    )
}
