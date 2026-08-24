package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * One reading session, keyed by its own id so a session can be updated as it progresses and closed when it
 * ends.
 *
 * [activeMillis] is the figure statistics add up — wall-clock time between the endpoints includes a locked
 * screen — and [endedAtEpochMillis] stays null while a session is open, which is what distinguishes a
 * force-quit from a clean close. Nothing writes these rows today (see ReadingStatsRepository).
 *
 * @property id the session's own key, so an open session can be updated and then closed.
 * @property documentId the book being read.
 * @property startedAtEpochMillis when the session began.
 * @property endedAtEpochMillis when it ended, or NULL while it is still open.
 * @property activeMillis time actually spent reading, which is what statistics sum.
 * @property startLocation where the session began, as the reader's own compact position string.
 * @property endLocation where it ended, or NULL while it is open.
 */
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val activeMillis: Long,
    val startLocation: String,
    val endLocation: String? = null,
)
