package com.tedd.teddreader.core.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * A document's identity, which is the URI it was imported from.
 *
 * Using the source URI rather than a generated key is what makes re-opening a book the app already holds
 * recognisable: the same file handed to the app twice resolves to the same id, so the second open reuses
 * the stored text and page layouts instead of importing again. It also means every derived row —
 * progress, bookmarks, search index, page layouts — keys off something the caller already has.
 *
 * Inline over the `String` so passing an id costs nothing while a bare string can never be mistaken for
 * one; blank is rejected here so no layer below has to check.
 *
 * @property value the source URI the document was imported from, which doubles as its identity.
 * @throws IllegalArgumentException if [value] is blank, since a blank id would collide with every other
 * blank one and key rows nothing can find again.
 */
@Serializable
@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId must not be blank." }
    }

    /** So a document id logs and prints as its own string, not as a wrapper around one. */
    override fun toString(): String = value
}

/**
 * What kind of document this is, resolved once at import from the file's name and MIME type and stored
 * with it.
 *
 * Nearly every behavioural fork in the reader starts here — whether text can be searched, whether pages
 * reflow, whether a page is an image — so the questions themselves are named as [isVisualPageFormat] and
 * [isImagePageFormat] rather than re-derived by comparing enum values at each site.
 *
 * [UNKNOWN] is a real state, not a failure: a file the app was handed and could not classify is still
 * listed, and is simply treated as having nothing reflowable to read.
 */
@Serializable
enum class DocumentFormat {
    TXT,
    PDF,
    EPUB,
    CBZ,
    IMAGE,
    UNKNOWN,
}

/**
 * Whether pages come as images rather than as reflowable text.
 *
 * True means there is no text to lay out, so pagination, search and text styling do not apply and page
 * images are fetched per page turn instead. Written as an exhaustive `when` on purpose: a new format has
 * to answer this question before it compiles.
 *
 * @receiver the format in question.
 * @return true for PDF, CBZ and single images; false for text formats and for an unclassified file.
 */
fun DocumentFormat.isVisualPageFormat(): Boolean = when (this) {
    DocumentFormat.PDF,
    DocumentFormat.CBZ,
    DocumentFormat.IMAGE,
        -> true
    DocumentFormat.TXT,
    DocumentFormat.EPUB,
    DocumentFormat.UNKNOWN,
        -> false
}

/**
 * Whether a page is a single picture that fills it — a comic page or a standalone image — as opposed to
 * PDF, which is also visual but whose pages are rendered.
 *
 * @receiver the format in question.
 * @return true for CBZ and single images, false for everything else — including PDF, whose pages are
 * rendered rather than stored as pictures.
 */
fun DocumentFormat.isImagePageFormat(): Boolean =
    this == DocumentFormat.CBZ || this == DocumentFormat.IMAGE

/**
 * Where a document came from and what to call it: the URI, the name to show, and what the platform said
 * about its type and size.
 *
 * [mimeType] is nullable because a picker does not always supply one; format detection therefore reads
 * the name as well and never depends on the MIME type alone. [displayName] is what the library shows and
 * what extension-based detection reads, which is why it is required rather than derived from the URI.
 *
 * @property sourceUri where the document lives, and the value a [DocumentId] is made of.
 * @property displayName the name to show, and what extension-based format detection reads.
 * @property mimeType what the platform said the file is, or null when the picker supplied nothing.
 * @property sizeBytes the file's size as reported, or 0 when unknown.
 * @throws IllegalArgumentException if [sourceUri] or [displayName] is blank, or [sizeBytes] is negative.
 */
@Serializable
data class DocumentLocation(
    val sourceUri: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
) {
    init {
        require(sourceUri.isNotBlank()) { "sourceUri must not be blank." }
        require(displayName.isNotBlank()) { "displayName must not be blank." }
        require(sizeBytes >= 0L) { "sizeBytes must be positive." }
    }
}

/**
 * What the library knows about a document without opening it: identity, origin, format, and the counts
 * and flags the list needs.
 *
 * Deliberately separate from the document's text, because listing a shelf of books must not load any of
 * them — the home screen renders entirely from these rows.
 *
 * The nullable counts mean "not known yet" rather than zero, which is what a progressively imported book
 * looks like before it finishes: the library reads a null [characterCount] as an import that has not
 * completed. [folderId] and [folderName] are constrained to be both present or both absent, so a row can
 * never claim to be in a folder that cannot be named.
 *
 * @property id the document's identity.
 * @property location where it came from and what to call it.
 * @property format what kind of document it is, resolved once at import.
 * @property addedAtEpochMillis when it was imported, which orders the library until it is first opened.
 * @property lastOpenedAtEpochMillis when it was last opened, or null while it never has been.
 * @property pageCount pages as last measured, or null when nothing has measured it yet.
 * @property characterCount characters of text, or null while the import has not finished — which is how
 * the library recognises a book that is still being parsed.
 * @property wordCount words of text, or null for the same reason as [characterCount].
 * @property isBookmarked whether the reader starred this book in the library.
 * @property folderId the folder this book is filed under, or null when it is not filed.
 * @property folderName that folder's name, present exactly when [folderId] is.
 * @throws IllegalArgumentException if any timestamp or count is negative, if [folderId] and [folderName]
 * are not both present or both absent, or if either is blank.
 */
@Serializable
data class DocumentMetadata(
    val id: DocumentId,
    val location: DocumentLocation,
    val format: DocumentFormat,
    val addedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long? = null,
    val pageCount: Int? = null,
    val characterCount: Long? = null,
    val wordCount: Long? = null,
    val isBookmarked: Boolean = false,
    val folderId: String? = null,
    val folderName: String? = null,
) {
    init {
        require(addedAtEpochMillis >= 0L) { "addedAtEpochMillis must be positive." }
        require(lastOpenedAtEpochMillis == null || lastOpenedAtEpochMillis >= 0L) {
            "lastOpenedAtEpochMillis must be positive."
        }
        require(pageCount == null || pageCount >= 0) { "pageCount must be positive." }
        require(characterCount == null || characterCount >= 0L) { "characterCount must be positive." }
        require(wordCount == null || wordCount >= 0L) { "wordCount must be positive." }
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
    }
}

/**
 * Whether this document's import has finished, judged the way the library judges it: a character count exists.
 *
 * The counts are written only when a document is fully parsed, so their absence is the library's own signal
 * that an import is still running — which is why a partially imported book shows no page count either. Named
 * here rather than re-derived at each call site, because "characterCount != null" states a storage fact where
 * the caller means to ask a domain question, and one call site's copy of that expression can drift from the
 * others.
 *
 * @receiver the library row to judge.
 * @return true when the import that produced this row completed.
 */
val DocumentMetadata.isImportFinished: Boolean get() = characterCount != null

/**
 * A half-open span of the joined document text, in absolute character offsets.
 *
 * Everything that has to point at the same passage across re-pagination uses these offsets: page spans,
 * search hits, bookmarks, section boundaries. Absolute rather than section-relative so two ranges from
 * different sections can be compared directly.
 *
 * @property start first character of the span, as an absolute document offset.
 * @property end one past its last character, so an empty span has [start] == [end].
 * @throws IllegalArgumentException if [start] is negative or [end] precedes [start].
 */
@Serializable
data class TextRange(
    val start: Long,
    val end: Long,
) {
    init {
        require(start >= 0L) { "TextRange start must be positive." }
        require(end >= start) { "TextRange end must greater than start." }
    }
}

/**
 * One unit of a document as its format divides it — a chapter, an EPUB spine item, a text file's whole
 * body — carrying its text and where that text sits in the document as a whole.
 *
 * The section is the unit of everything expensive: parsing, storing, decoding block structure, and
 * measuring pages all happen one section at a time, which is what lets a book be opened while the rest
 * of it is still being imported. [index] is the position in the document's own order, and stays stable
 * as later sections arrive, so a stored position keeps pointing at the same passage.
 *
 * @property index this section's position in the document's own order, stable as later sections arrive.
 * @property text the section's text, already line-ending normalised.
 * @property range where that text sits in the whole document, in absolute offsets.
 * @property title the section's own heading when the format carries one, else null.
 * @throws IllegalArgumentException if [index] is negative.
 */
@Serializable
data class ReaderSection(
    val index: Int,
    val text: String,
    val range: TextRange,
    val title: String? = null,
) {
    init {
        require(index >= 0) { "ReaderSection index must be positive." }
    }
}

/**
 * One entry in a document's table of contents: its title, its depth, and where it points.
 *
 * The target is carried as both [spineIndex] and [offset] so an entry stays usable while a book is still
 * being imported — the spine position is known from the start, the absolute offset only once the sections
 * before it have been parsed.
 *
 * @property title the entry as the book writes it.
 * @property level nesting depth, starting at 1 for a top-level entry.
 * @property spineIndex the spine item this entry points into — known from the start of an import.
 * @property offset the absolute text offset it points at, resolved once the sections before it are parsed.
 * @throws IllegalArgumentException if [title] is blank, [level] is below 1, or either position is
 * negative.
 */
@Serializable
data class ReaderNavigationItem(
    val title: String,
    val level: Int,
    val spineIndex: Int,
    val offset: Long,
) {
    init {
        require(title.isNotBlank()) { "ReaderNavigationItem title must not be blank." }
        require(level >= 1) { "ReaderNavigationItem level must be positive." }
        require(spineIndex >= 0) { "ReaderNavigationItem spineIndex must be positive." }
        require(offset >= 0L) { "ReaderNavigationItem offset must be positive." }
    }
}

/**
 * A document's table of contents, with the heading the book gives it ("Contents", "목차") when it has one.
 * Absent entirely for a format that carries no navigation, which is why the reader treats an empty list
 * and a null navigation the same way: nothing to show.
 *
 * @property heading the book's own name for its contents, or null when it gives none.
 * @property items the entries in document order; empty means a book with no usable navigation.
 */
@Serializable
data class ReaderNavigation(
    val heading: String? = null,
    val items: List<ReaderNavigationItem> = emptyList(),
)

/**
 * A document as the reader reads it: its sections, their block structure, and its navigation.
 *
 * This is the parsed form, distinct from [DocumentMetadata], and for a progressively imported book it
 * holds what has been parsed *so far* — the reader re-reads it after every import batch and sees it grow.
 *
 * [characterCount] and [wordCount] are computed from the sections rather than stored, so they can never
 * disagree with the text actually present. [characterCount] is also the fingerprint a stored page layout
 * is checked against: if it has changed, the offsets a layout was measured against no longer describe
 * this document, and the layout is discarded rather than trusted.
 *
 * @property id the document's identity.
 * @property format what kind of document it is, which decides whether any of the text below applies.
 * @property title the book's title, for the reader's own chrome.
 * @property sections the sections parsed so far, in document order.
 * @property pageCount pages as last measured, or null when nothing has measured this document.
 * @property navigation the table of contents, or null for a format that carries none.
 */
@Serializable
data class ReaderDocument(
    val id: DocumentId,
    val format: DocumentFormat,
    val title: String,
    val sections: List<ReaderSection>,
    val pageCount: Int? = null,
    /**
     * Structure of the joined section text, for a format that carries any. Empty means the text reads
     * as written, which is the whole story for a plain text file. Ranges address the sections joined
     * by a single newline, the same text pagination and reading position work on.
     */
    val blocks: List<ReaderBlock> = emptyList(),
    val navigation: ReaderNavigation? = null,
) {
    init {
        require(title.isNotBlank()) { "ReaderDocument title must not be blank." }
        require(pageCount == null || pageCount >= 0) { "pageCount must be positive." }
    }

    /** Characters across every parsed section, recomputed by summing on each access rather than cached. */
    val characterCount: Long get() = sections.sumOf { section -> section.text.length.toLong() }

    /** Words across every parsed section, recomputed by summing on each access rather than cached. */
    val wordCount: Long get() = sections.sumOf { section -> section.text.wordCount().toLong() }
}

/**
 * One occurrence of a search query in a document, with enough context to show and to jump to.
 *
 * [location] is what a tap navigates to and [range] the exact span in document offsets, kept separate
 * because the first is a position the reader can be moved to and the second is what highlighting and
 * de-duplication compare. [query] travels with the result so a screen can highlight the match without
 * having to remember what was asked.
 *
 * @property documentId the document the match was found in.
 * @property snippet the surrounding text to show in a result row.
 * @property location where a tap on the result should send the reader.
 * @property sectionTitle the section the match sits in, when that section has a title.
 * @property range the exact span of the match in absolute document offsets, for highlighting.
 * @property query what was searched for, carried along so a row can highlight without remembering.
 */
@Serializable
data class SearchResult(
    val documentId: DocumentId,
    val snippet: String,
    val location: ReaderLocation,
    val sectionTitle: String? = null,
    val range: TextRange? = null,
    val query: String = "",
)

/**
 * Whether searching this document can return anything: it must have reflowable text, and that text must
 * actually be there.
 *
 * The second half matters while a book is still importing — a document whose sections exist but are still
 * blank would otherwise offer a search that can only answer "no results".
 *
 * @receiver the document to consider.
 * @return true when the format reflows text *and* at least one section actually has some, so the reader
 * only offers a search that can find something.
 */
fun ReaderDocument.isTextSearchSupported(): Boolean =
    !format.isVisualPageFormat() && sections.any { section -> section.text.isNotBlank() }

/**
 * One day's reading of one document, as a statistics screen would chart it.
 *
 * Aggregated per calendar date rather than per session, since that is the granularity a chart shows.
 * Nothing produces these yet — reading sessions are not recorded anywhere (see ReadingStatsRepository) —
 * so this is the shape the feature will read, not a shape with data behind it.
 *
 * @property documentId the document read.
 * @property date the calendar day, which is the granularity a chart shows.
 * @property activeMillis time actually spent reading that day.
 * @property wordsRead words covered that day.
 * @throws IllegalArgumentException if [activeMillis] or [wordsRead] is negative.
 */
@Serializable
data class ReadingHistoryEntry(
    val documentId: DocumentId,
    val date: LocalDate,
    val activeMillis: Long,
    val wordsRead: Long,
) {
    init {
        require(activeMillis >= 0L) { "activeMillis must be positive." }
        require(wordsRead >= 0L) { "wordsRead must be positive." }
    }
}

/**
 * The number of whitespace-separated words in this text, counted in a single left-to-right pass
 * with no intermediate allocations. A word boundary is any transition from a whitespace character
 * to a non-whitespace character; consecutive whitespace of any kind collapses into a single
 * separator, and leading/trailing whitespace is effectively skipped because no transition into a
 * word is recorded for it beyond incrementing the count at entry.
 *
 * Whitespace is defined by [Char.isWhitespace], which covers ASCII control whitespace
 * (space, tab, newline, vertical tab, form feed, carriage return) and Unicode category-Zs
 * characters (non-breaking space, en/em space, ideographic space, and others). This gives
 * consistent behaviour across JVM and Kotlin/Native, where the former Java `Regex("\\s+")`
 * split only recognised ASCII whitespace on JVM but matched Unicode whitespace on Native via
 * ICU — a silent cross-platform inconsistency the old implementation carried.
 *
 * @receiver the text to count.
 * @return 0 for blank or empty text; otherwise the number of non-whitespace tokens separated by
 *   one or more whitespace characters.
 */
fun String.wordCount(): Int {
    var count = 0
    var inWord = false
    for (ch in this) {
        if (ch.isWhitespace()) {
            inWord = false
        } else {
            if (!inWord) count++
            inWord = true
        }
    }
    return count
}
