package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 리더 자체의 위치 타입이 작성하는 축약형 `prefix:…` 문자열로 위치를 저장한 장소입니다.
 *
 * 기본 키는 생성된 값이 아닌 조합한 id이므로 같은 위치를 두 번 저장해도 한 행을 업서트합니다. 위치는 텍스트 오프셋,
 * spine과 오프셋, 페이지 번호라는 서로 다른 세 가지 형태를 담아야 하므로 텍스트로 저장합니다. nullable 열 세 개보다
 * 하나의 열을 비교하거나 grep하는 편이 낫습니다.
 *
 * @property id 위치를 다시 저장해도 결과가 같게 만드는 조합형 `"<documentId>:<location>"` 키입니다.
 * @property documentId 위치가 속한 문서입니다.
 * @property readerLocation 리더 자체의 축약형 `prefix:…` 문자열로 표현한 위치입니다.
 * @property label 독자가 붙인 위치 이름이며, toggle만으로 저장했으면 NULL입니다.
 * @property note 해당 구절에 대한 독자의 메모이며, 없으면 NULL입니다.
 * @property createdAtEpochMillis 저장한 시각이며 북마크 화면의 순서를 결정합니다.
 */
@Entity(
    tableName = "bookmarks",
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
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val readerLocation: String,
    val label: String? = null,
    val note: String? = null,
    val createdAtEpochMillis: Long,
)
