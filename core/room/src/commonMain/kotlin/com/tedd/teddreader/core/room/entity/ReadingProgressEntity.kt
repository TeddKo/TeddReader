package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 한 문서의 독서 위치입니다. 문서 id 자체가 기본 키이므로 책마다 행이 정확히 하나이며, 페이지를 넘길 때 이력을
 * 추가하지 않고 교체합니다.
 *
 * [currentPageIndex]와 [totalPageCount]는 리더가 마지막으로 표시한 값입니다. 화면이 아직 레이아웃을 만들기
 * 전에도 숫자를 표시할 수 있도록 영속적인 [readerLocation] 옆에 보관하지만, 실제 재개에는 위치를 사용합니다.
 * 아직 측정 중인 책은 주장할 전체 페이지 수가 없으므로 [totalPageCount]는 nullable입니다.
 *
 * @property documentId 책이자 기본 키입니다. 문서마다 위치 하나를 제자리에서 교체합니다.
 * @property readerLocation 리더 자체의 축약형 `prefix:…` 문자열로 표현한 영속적인 위치입니다.
 * @property currentPageIndex 레이아웃 전에 진행 상황을 표시하기 위한 마지막 표시 페이지입니다.
 * @property totalPageCount 해당 페이지를 표시할 때 알고 있던 페이지 수이며, 측정된 값이 없으면 NULL입니다.
 * @property updatedAtEpochMillis 행을 기록한 시각이며, 현재 리더는 0을 전달합니다.
 */
@Entity(
    tableName = "reading_progress",
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
data class ReadingProgressEntity(
    @PrimaryKey val documentId: String,
    val readerLocation: String,
    val currentPageIndex: Int,
    val totalPageCount: Int? = null,
    val updatedAtEpochMillis: Long,
)
