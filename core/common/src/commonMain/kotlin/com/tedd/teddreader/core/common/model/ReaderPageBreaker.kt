package com.tedd.teddreader.core.common.model

/**
 * Reports where the reader's own text layout puts page boundaries.
 *
 * Pagination has to agree with the renderer to fill a page without hiding its tail, and neither half
 * of that agreement can be estimated. A character-width guess cannot see word wrapping or per-glyph
 * advances, and a `viewportHeight / lineHeight` line count cannot see that a line box grows to the
 * font's natural height once the reader's line height drops below it, or that one line of the page
 * is taller because a fallback font covered it. The UI layer owns the real layout and measures both.
 */
fun interface ReaderPageBreaker {
    /** Offset of the first character of every page, ascending, starting at 0. */
    fun pageStarts(text: String): IntArray
}
