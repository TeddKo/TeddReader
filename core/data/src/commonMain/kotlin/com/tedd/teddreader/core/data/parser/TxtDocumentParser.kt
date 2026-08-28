package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import org.koin.core.annotation.Single

/**
 * Turns a plain-text file into a [ReaderDocument] with exactly one section spanning the whole text.
 *
 * A `.txt` file carries no structure at all — no chapters, no headings, no markup — so unlike the EPUB
 * or comic parsers there is nothing here to detect or split on: the entire file becomes a single
 * [ReaderSection]. Line endings are normalized to `\n` before anything else runs, because a `\r\n` or
 * bare `\r` left over from a Windows- or classic-Mac-authored file would otherwise throw off every
 * character offset a reading position, search hit, or [TextRange] depends on downstream.
 */
@Single
class TxtDocumentParser {
    /**
     * Wraps [text] as a one-section document, normalizing its line endings first.
     *
     * @param id identity of the source file, carried through unchanged.
     * @param title label used for the document only; a TXT file has no chapter title, so its one section
     *   intentionally carries `null` as its title.
     * @param text the file's full decoded contents; how the original bytes were decoded into this
     *   string (encoding detection, BOM handling) happens before this call, not here.
     * @return a [ReaderDocument] of [DocumentFormat.TXT] with one [ReaderSection] holding all of
     *   [text], its line endings normalized to `\n`.
     */
    fun parse(
        id: DocumentId,
        title: String,
        text: String,
    ): ReaderDocument {
        val normalizedText = text.replace("\r\n", "\n").replace('\r', '\n')
        return ReaderDocument(
            id = id,
            format = DocumentFormat.TXT,
            title = title,
            sections = listOf(
                ReaderSection(
                    index = 0,
                    title = null,
                    text = normalizedText,
                    range = TextRange(0L, normalizedText.length.toLong()),
                ),
            ),
        )
    }
}
