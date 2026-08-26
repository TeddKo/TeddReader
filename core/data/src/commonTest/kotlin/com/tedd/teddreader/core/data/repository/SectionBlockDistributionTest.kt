package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.rebasedBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards [distributeBlocksIntoSections] against the per-section `blocksIn`/`rebasedBy` it replaced. The
 * reference implementation those cases check against is the exact expression the old
 * [DocumentRepositoryImpl.persistParsedDocument] ran, so any divergence in boundary-crossing, zero-width,
 * empty-input or ordering behavior fails here rather than silently changing what a stored section holds.
 */
class SectionBlockDistributionTest {
    /**
     * The reference the sweep must match block-for-block: the pre-refactor expression, run per section.
     * Kept here rather than shared with production so a regression in the helper cannot also silently
     * change the reference it is compared against.
     *
     * @param sections the section ranges to distribute across.
     * @param blocks the document's flat block list.
     * @return each section's overlapping blocks, rebased section-relative, exactly as the old code built.
     */
    private fun referenceDistribution(
        sections: List<TextRange>,
        blocks: List<ReaderBlock>,
    ): List<List<ReaderBlock>> = sections.map { section ->
        blocks.blocksIn(section.start, section.end).rebasedBy(section.start)
    }

    /**
     * A paragraph block over `[start, end)`, the simplest kind carrying real text offsets, so a case can
     * name only the offsets it is testing.
     *
     * @param start the block's absolute start offset.
     * @param end the block's absolute end offset; equal to [start] makes a zero-width block.
     * @return the paragraph block.
     */
    private fun block(start: Long, end: Long): ReaderBlock =
        ReaderBlock(kind = ReaderBlockKind.PARAGRAPH, range = TextRange(start, end))

    /**
     * Confirms an empty section list yields an empty result, and empty blocks yield one empty list per
     * section — the two degenerate inputs the sweep short-circuits before sorting anything.
     */
    @Test
    fun emptyInputsMatchReference() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20))

        assertEquals(emptyList(), distributeBlocksIntoSections(emptyList(), listOf(block(0, 5))))
        assertEquals(
            referenceDistribution(sections, emptyList()),
            distributeBlocksIntoSections(sections, emptyList()),
        )
        assertEquals(List(2) { emptyList<ReaderBlock>() }, distributeBlocksIntoSections(sections, emptyList()))
    }

    /**
     * Confirms blocks that sit wholly inside one section land only in that section, rebased by its start,
     * matching the reference for the ordinary non-crossing case.
     */
    @Test
    fun containedBlocksMatchReference() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20), TextRange(20, 30))
        val blocks = listOf(block(0, 5), block(6, 10), block(12, 18), block(20, 25))

        assertEquals(
            referenceDistribution(sections, blocks),
            distributeBlocksIntoSections(sections, blocks),
        )
    }

    /**
     * Confirms a non-zero block that straddles a section boundary is emitted into every section it
     * overlaps, not just its starting one — the half-open overlap rule [blocksIn] applies.
     */
    @Test
    fun boundaryCrossingBlockLandsInEveryOverlappingSection() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20), TextRange(20, 30))
        val blocks = listOf(block(5, 25))

        val result = distributeBlocksIntoSections(sections, blocks)

        assertEquals(referenceDistribution(sections, blocks), result)
        assertTrue(result[0].isNotEmpty(), "crossing block must reach the first section")
        assertTrue(result[1].isNotEmpty(), "crossing block must reach the middle section")
        assertTrue(result[2].isNotEmpty(), "crossing block must reach the last section")
    }

    /**
     * Confirms zero-width blocks follow [blocksIn]'s point rule: kept when the point sits in the section's
     * half-open range, and kept in a degenerate empty section when the point equals its shared offset,
     * exactly matching the reference across ordinary and empty sections.
     */
    @Test
    fun zeroWidthBlocksMatchReference() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 10), TextRange(10, 20))
        val blocks = listOf(block(0, 0), block(10, 10), block(15, 15), block(20, 20))

        assertEquals(
            referenceDistribution(sections, blocks),
            distributeBlocksIntoSections(sections, blocks),
        )
    }

    /**
     * Confirms both output orders under input that does not form an ascending partition, so the fallback
     * to per-section [blocksIn] is exercised: blocks keep their document order inside each section list,
     * and the section lists keep [sections]' input order, even though the sections are given out of
     * ascending order. Matching the reference here guards that the off-nominal fallback still preserves
     * both orders exactly.
     */
    @Test
    fun preservesBlockAndSectionOrderUnderUnsortedInput() {
        val sections = listOf(TextRange(20, 30), TextRange(0, 10), TextRange(10, 20))
        val blocks = listOf(block(22, 24), block(2, 4), block(12, 14), block(20, 21), block(0, 1))

        assertEquals(
            referenceDistribution(sections, blocks),
            distributeBlocksIntoSections(sections, blocks),
        )
    }

    /**
     * Confirms a zero-width block exactly on an ordinary partition boundary is owned only by the
     * following section under [blocksIn]'s half-open rule. Unlike [zeroWidthBlocksMatchReference], this
     * input remains an ascending non-empty partition and therefore exercises the optimized sweep itself.
     */
    @Test
    fun zeroWidthBlockAtPartitionBoundaryMatchesReferenceOnFastPath() {
        val sections = listOf(TextRange(0, 10), TextRange(10, 20))
        val blocks = listOf(block(10, 10))

        val result = distributeBlocksIntoSections(sections, blocks)

        assertEquals(referenceDistribution(sections, blocks), result)
        assertTrue(result[0].isEmpty(), "boundary point must not belong to the preceding half-open section")
        assertTrue(result[1].isNotEmpty(), "boundary point must belong to the following section")
    }

    /**
     * Confirms the sweep does not re-read the whole block list once per section. A [CountingBlockList]
     * records how often each block index is read; with many sections and few blocks, the old
     * `blocksIn` per section read every block once per section (`reads >= sections * blocks`), so the
     * assertion that the busiest block is read far fewer times than the section count is exactly what
     * fails if the O(S * B) rescan comes back.
     */
    @Test
    fun doesNotRescanEveryBlockPerSection() {
        val sectionCount = 200
        val sections = (0 until sectionCount).map { TextRange(it * 10L, it * 10L + 10L) }
        val rawBlocks = listOf(block(5, 6), block(1005, 1006), block(1990, 1991))
        val counting = CountingBlockList(rawBlocks)

        val result = distributeBlocksIntoSections(sections, counting)

        assertEquals(
            referenceDistribution(sections, rawBlocks),
            result,
        )
        val busiest = counting.maxReadsPerIndex()
        assertTrue(
            busiest < sectionCount,
            "a block was read $busiest times across $sectionCount sections; the per-section rescan is back",
        )
    }
}

/**
 * A [ReaderBlock] list that counts how many times each index is read, so a test can prove
 * [distributeBlocksIntoSections] does not touch every block once per section.
 *
 * Backed by a plain list and delegating [size], it is a read-only view whose only added behavior is the
 * per-index tally; it never mutates or reorders the blocks it wraps.
 *
 * @property backing the blocks this view serves and counts reads of.
 */
private class CountingBlockList(private val backing: List<ReaderBlock>) : AbstractList<ReaderBlock>() {
    /** Read tally per index, grown lazily so an index never read simply stays absent. */
    private val readsByIndex = HashMap<Int, Int>()

    /** The wrapped list's size, unchanged. */
    override val size: Int get() = backing.size

    /**
     * Serves the [index]th block and records the read.
     *
     * @param index the position being read.
     * @return the block at [index] from [backing].
     */
    override fun get(index: Int): ReaderBlock {
        readsByIndex[index] = (readsByIndex[index] ?: 0) + 1
        return backing[index]
    }

    /**
     * The largest read count any single index accumulated, or zero when nothing was read.
     *
     * @return the busiest index's read count, the figure a per-section rescan would inflate to at least
     *   the section count.
     */
    fun maxReadsPerIndex(): Int = readsByIndex.values.maxOrNull() ?: 0
}

/**
 * Guards [concatPageStarts] against the `existing.toList() + appended.flatMap { it.toList() }` boxing it
 * replaced: the joined array must hold the pieces in order, for the empty, single, many, and large-offset
 * shapes the append path actually produces.
 */
class ConcatPageStartsTest {
    /**
     * Confirms an empty existing array and empty appended list join to an empty array, the base case an
     * append with nothing measured yet reaches.
     */
    @Test
    fun joinsEmptyPiecesIntoEmptyArray() {
        assertEquals(emptyList(), concatPageStarts(LongArray(0), emptyList()).toList())
    }

    /**
     * Confirms a single appended array is placed after the existing offsets in order, the ordinary
     * one-section-batch shape.
     */
    @Test
    fun joinsExistingThenOneAppendedArrayInOrder() {
        val existing = longArrayOf(0L, 100L, 200L)
        val appended = listOf(longArrayOf(300L, 400L))

        assertEquals(listOf(0L, 100L, 200L, 300L, 400L), concatPageStarts(existing, appended).toList())
    }

    /**
     * Confirms multiple appended arrays are concatenated in list order, each after the last, so a
     * multi-section batch extends the layout in reading order rather than interleaving.
     */
    @Test
    fun joinsMultipleAppendedArraysInListOrder() {
        val existing = longArrayOf(0L, 10L)
        val appended = listOf(longArrayOf(20L, 30L), longArrayOf(), longArrayOf(40L, 50L, 60L))

        assertEquals(
            listOf(0L, 10L, 20L, 30L, 40L, 50L, 60L),
            concatPageStarts(existing, appended).toList(),
        )
    }

    /**
     * Confirms large, realistic-book-sized offsets survive the join in order and at the right length,
     * the scale [encodePageStartsBlob]'s own test names (3.5M-character books, tens of thousands of
     * pages).
     */
    @Test
    fun joinsLargeAscendingOffsetsInOrder() {
        val existing = LongArray(10_000) { it * 210L }
        val appendedFirst = LongArray(5_000) { (10_000 + it) * 210L }
        val appendedSecond = LongArray(5_000) { (15_000 + it) * 210L }

        val combined = concatPageStarts(existing, listOf(appendedFirst, appendedSecond))

        assertEquals(20_000, combined.size)
        assertEquals(LongArray(20_000) { it * 210L }.toList(), combined.toList())
    }
}
