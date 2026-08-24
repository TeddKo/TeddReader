package com.tedd.teddreader.core.common.model

/**
 * Reports where the reader's own text layout puts page boundaries.
 *
 * Pagination has to agree with the renderer to fill a page without hiding its tail, and neither half
 * of that agreement can be estimated. A character-width guess cannot see word wrapping or per-glyph
 * advances, and a `viewportHeight / lineHeight` line count cannot see that a line box grows to the
 * font's natural height once the reader's line height drops below it, or that one line of the page
 * is taller because a fallback font covered it. The UI layer owns the real layout and measures both.
 *
 * @see pageStarts
 */
fun interface ReaderPageBreaker {
    /**
     * Measures where the pages of one stretch of text begin.
     *
     * @param text the text to lay out — one section's worth, as the reader will draw it.
     * @param blocks that text's block structure, so an image occupies the box it will really be drawn in
     * rather than the one character it stands as.
     * @return the offset of the first character of every page, ascending, starting at 0; empty for empty
     * text.
     */
    fun pageStarts(text: String, blocks: List<ReaderBlock>): IntArray
}
