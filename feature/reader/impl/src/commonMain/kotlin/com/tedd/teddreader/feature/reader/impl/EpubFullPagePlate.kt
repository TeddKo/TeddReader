package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.isBlankIgnoringObjects

/**
 * The picture a page is given over to entirely, or null when the page has reading on it.
 *
 * A plate that shares its page with no text was still laid out inside the text flow, which pins it to
 * the top of the page and leaves the rest blank. A cover is not treated that way and never looked
 * wrong, so a page holding nothing but one picture is drawn the same way the cover is: filling the
 * page, centred in it.
 *
 * A page carrying even one word of text is left alone. The picture belongs in the flow there — moving
 * it would tear it out of the paragraph it was written in.
 */
internal fun epubFullPagePlate(text: String, blocks: List<ReaderBlock>): ReaderBlock? {
    blocks.firstOrNull { it.kind == ReaderBlockKind.COVER_IMAGE }?.let { return it }
    if (!text.isBlankIgnoringObjects()) return null
    // Only one: two pictures sharing a page still have to be stacked by the text layout, which is the
    // only thing that knows the order they were written in.
    return blocks.singleOrNull { it.kind == ReaderBlockKind.IMAGE && it.imageHref != null }
}
