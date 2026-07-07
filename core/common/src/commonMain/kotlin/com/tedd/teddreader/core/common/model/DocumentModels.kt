package com.tedd.teddreader.core.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId must not be blank." }
    }

    override fun toString(): String = value
}

@Serializable
enum class DocumentFormat {
    TXT,
    PDF,
    EPUB,
    UNKNOWN,
}

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
) {
    init {
        require(addedAtEpochMillis >= 0L) { "addedAtEpochMillis must be positive." }
        require(lastOpenedAtEpochMillis == null || lastOpenedAtEpochMillis >= 0L) {
            "lastOpenedAtEpochMillis must be positive."
        }
        require(pageCount == null || pageCount >= 0) { "pageCount must be positive." }
        require(characterCount == null || characterCount >= 0L) { "characterCount must be positive." }
        require(wordCount == null || wordCount >= 0L) { "wordCount must be positive." }
    }
}

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

@Serializable
data class ReaderDocument(
    val id: DocumentId,
    val format: DocumentFormat,
    val title: String,
    val sections: List<ReaderSection>,
    val pageCount: Int? = null,
) {
    init {
        require(title.isNotBlank()) { "ReaderDocument title must not be blank." }
        require(pageCount == null || pageCount >= 0) { "pageCount must be positive." }
    }

    val characterCount: Long get() = sections.sumOf { section -> section.text.length.toLong() }
    val wordCount: Long get() = sections.sumOf { section -> section.text.wordCount().toLong() }
}

@Serializable
data class SearchResult(
    val documentId: DocumentId,
    val snippet: String,
    val location: ReaderLocation,
    val sectionTitle: String? = null,
    val range: TextRange? = null,
    val query: String = "",
)

fun ReaderDocument.isTextSearchSupported(): Boolean =
    format != DocumentFormat.PDF && sections.any { section -> section.text.isNotBlank() }

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

fun String.wordCount(): Int = trim()
    .takeIf { text -> text.isNotEmpty() }
    ?.split(Regex("\\s+"))
    ?.count { word -> word.isNotBlank() }
    ?: 0
