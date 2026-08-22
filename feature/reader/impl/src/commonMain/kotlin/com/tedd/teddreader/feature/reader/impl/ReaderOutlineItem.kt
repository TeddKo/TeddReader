package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.ReaderLocation

/**
 * One entry in the reader's table of contents — either taken from the document's own navigation
 * (an EPUB's nav document) or synthesized one-per-section/page when it has none.
 *
 * @property title the text shown for this entry.
 * @property location where selecting this entry moves the reader to.
 * @property level nesting depth for indentation in the outline list; 1 is top-level, and deeper
 *   numbers indent further. Defaults to 1 for a document with no real hierarchy to report.
 */
@Immutable
data class ReaderOutlineItem(
    val title: String,
    val location: ReaderLocation,
    val level: Int = 1,
)
