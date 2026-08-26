package com.tedd.teddreader.core.data.repository

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.blocksIn
import com.tedd.teddreader.core.common.model.rebasedBy

/**
 * Splits one document's flat block list across its sections the way [blocksIn] followed by [rebasedBy]
 * does, but with a single sweep of both lists instead of a full rescan of every block for every section.
 *
 * Persisting a book stores each section's blocks section-relative, so the caller used to run
 * `document.blocks.blocksIn(section.range.start, section.range.end).rebasedBy(section.range.start)` inside
 * a `sections.map { }` (see [DocumentRepositoryImpl.persistParsedDocument]). That is O(S * B): every one of
 * the book's blocks is tested against every section, and a 3.5M-character book with tens of thousands of
 * blocks and hundreds of sections pays that product on the import thread. For the shape a real book has —
 * the ascending, non-overlapping section partition [TextPageLayoutEngine] guarantees — this helper is
 * O((S + B) log B): it sorts the blocks by start once, then walks sections and blocks together with a
 * forward cursor plus a small carry-over list for the rare block that crosses a section boundary, so each
 * block is emitted only into the sections it actually overlaps and is never re-tested against a section it
 * cannot reach.
 *
 * The membership rule is [blocksIn]'s exactly, kept identical so nothing a reader sees changes:
 * - A non-zero-width block (`range.start != range.end`) belongs to every section whose half-open
 *   `[start, end)` its own half-open span overlaps — `block.start < section.end && block.end > section.start`.
 *   A block that crosses a section boundary is therefore emitted into every section it spans, not just one.
 * - A zero-width block (`range.start == range.end`) belongs to a section when its point sits in the section's
 *   half-open range (`section.start <= block.start < section.end`), plus the one degenerate case [blocksIn]
 *   also admits: an empty section (`section.start == section.end`) whose point equals that shared offset.
 *
 * Within each returned section list the blocks keep their original order from [blocks], and the returned
 * lists are in the same order as [sections]; both orders match what the old per-section `blocksIn`/`map`
 * produced. Each block is copied section-relative by the owning section's start via [rebasedBy], so the
 * result is drop-in for the stored, section-relative form.
 *
 * Correctness never depends on the fast path's assumption. Whenever [sections] is not the ascending,
 * non-overlapping, non-empty partition [isAscendingDisjointPartition] checks for — an out-of-order,
 * overlapping, or empty section slipped in — this falls back to exactly the old per-section
 * `blocks.blocksIn(...).rebasedBy(...)`, so the answer is identical to [blocksIn] for any input at the cost
 * of the O(S * B) scan only in that off-nominal case. The forward-cursor sweep is taken only when its
 * monotonic-cursor invariants are known to hold, so it never has to reason about a block a section-order
 * reversal would have stranded behind the cursor.
 *
 * @param sections the section ranges to distribute blocks across, in the order the result must follow.
 * @param blocks the document's flat block list, in the document order each section list must preserve.
 * @return one list per entry in [sections], in [sections]' order, each holding that section's overlapping
 *   blocks in [blocks]' order, already rebased section-relative by that section's start.
 */
internal fun distributeBlocksIntoSections(
    sections: List<TextRange>,
    blocks: List<ReaderBlock>,
): List<List<ReaderBlock>> {
    if (sections.isEmpty()) return emptyList()
    if (blocks.isEmpty()) return List(sections.size) { emptyList() }

    if (!sections.isAscendingDisjointPartition()) {
        return sections.map { section ->
            blocks.blocksIn(section.start, section.end).rebasedBy(section.start)
        }
    }

    val blockOrder = blocks.indices.sortedWith(
        compareBy({ blocks[it].range.start }, { it }),
    )

    val gathered = List(sections.size) { mutableListOf<Int>() }
    var blockCursor = 0
    var carryOver = mutableListOf<Int>()

    for (sectionIndex in sections.indices) {
        val section = sections[sectionIndex]
        val target = gathered[sectionIndex]

        val nextCarryOver = mutableListOf<Int>()
        for (carriedBlockIndex in carryOver) {
            if (blockMatchesSection(blocks[carriedBlockIndex], section)) target += carriedBlockIndex
            if (blocks[carriedBlockIndex].range.end > section.end) nextCarryOver += carriedBlockIndex
        }

        while (blockCursor < blockOrder.size && blocks[blockOrder[blockCursor]].range.start < section.end) {
            val blockIndex = blockOrder[blockCursor]
            val block = blocks[blockIndex]
            if (blockMatchesSection(block, section)) target += blockIndex
            if (block.range.end > section.end) nextCarryOver += blockIndex
            blockCursor += 1
        }

        carryOver = nextCarryOver
    }

    return sections.indices.map { sectionIndex ->
        val sectionStart = sections[sectionIndex].start
        gathered[sectionIndex]
            .sorted()
            .map { blockIndex -> blocks[blockIndex].rebasedBy(sectionStart) }
    }
}

/**
 * Whether these section ranges are the ascending, non-overlapping partition the fast sweep in
 * [distributeBlocksIntoSections] relies on: strictly ascending by start, each range non-empty
 * (`start < end`), and no range starting before the previous one ends. This is exactly the shape
 * [TextPageLayoutEngine] guarantees — a page never spans two sections, so sections tile the document in
 * order — and the moment it does not hold (an out-of-order, overlapping, or empty section slipped in),
 * the caller falls back to per-section [blocksIn] so correctness never depends on the assumption.
 *
 * @receiver the section ranges to inspect, in the order they will be distributed into.
 * @return true only when the sweep's monotonic-cursor assumptions all hold.
 */
private fun List<TextRange>.isAscendingDisjointPartition(): Boolean {
    for (index in indices) {
        val current = this[index]
        if (current.start >= current.end) return false
        if (index > 0 && current.start < this[index - 1].end) return false
    }
    return true
}

/**
 * Whether [block] belongs to [section] under [blocksIn]'s exact half-open, zero-width-aware rule.
 *
 * Kept a single function so the sweep in [distributeBlocksIntoSections] and any equivalence test share one
 * definition of membership with [blocksIn] rather than each restating the boundary arithmetic.
 *
 * @param block the candidate block, in absolute document offsets.
 * @param section the section range to test membership against.
 * @return true exactly when `listOf(block).blocksIn(section.start, section.end)` would keep [block].
 */
private fun blockMatchesSection(block: ReaderBlock, section: TextRange): Boolean =
    if (block.range.start == block.range.end) {
        block.range.start in section.start until section.end ||
            (block.range.start == section.end && section.end == section.start)
    } else {
        block.range.start < section.end && block.range.end > section.start
    }

/**
 * Joins an already-decoded page-start array with the freshly measured per-section arrays into one
 * `LongArray`, in order, without ever boxing an offset into a `List<Long>` on the way.
 *
 * [DocumentRepositoryImpl.appendMeasuredPageStarts] used to grow the stored layout by turning both the
 * existing offsets and every new section's offsets into `List<Long>`, concatenating those lists, then
 * calling `toLongArray()` — three boxing passes over data that is `Long` on both ends. Extending a large
 * book one batch at a time, that boxing is paid on the import thread for every batch. This sums the final
 * length once, allocates the destination once, and `copyInto`s each source array into place, so the only
 * allocation is the array the codec is about to encode.
 *
 * The pieces are copied in the order given: [existing] first, then each array of [appended] in list order.
 * Reading order is the caller's responsibility exactly as before — this helper concatenates, it does not
 * sort — so the caller must pass the sections' arrays already in reading order, which is what
 * [DocumentRepositoryImpl.appendMeasuredPageStarts] does by measuring them in section order.
 *
 * @param existing the page starts already stored for the layout, decoded from its blob.
 * @param appended each newly imported section's measured page starts, in the reading order they extend the
 *   layout with.
 * @return one array holding [existing] followed by every array in [appended], concatenated in order.
 */
internal fun concatPageStarts(existing: LongArray, appended: List<LongArray>): LongArray {
    val total = existing.size + appended.sumOf { it.size }
    val combined = LongArray(total)
    existing.copyInto(combined, destinationOffset = 0)
    var writeOffset = existing.size
    for (array in appended) {
        array.copyInto(combined, destinationOffset = writeOffset)
        writeOffset += array.size
    }
    return combined
}
