package com.tedd.teddreader.core.common.model

/**
 * Reports where the reader actually breaks lines for a given text.
 *
 * Pagination has to agree with the renderer to fill a page: a character-width estimate cannot see
 * word wrapping or per-glyph advances, so it either stops short of the viewport or pushes the tail
 * of the page past the clip. The UI layer owns the real text layout and supplies it here.
 */
fun interface ReaderLineBreaker {
    /** Offset of the first character of every rendered line, ascending, starting at 0. */
    fun lineStarts(text: String): IntArray
}
