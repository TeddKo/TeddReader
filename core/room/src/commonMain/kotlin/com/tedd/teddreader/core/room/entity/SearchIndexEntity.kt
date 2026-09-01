package com.tedd.teddreader.core.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * 문서에 저장된 텍스트의 한 섹션이며 리더와 검색이 함께 읽는 행입니다.
 *
 * 문서와 섹션 인덱스를 키로 사용하므로 점진적 가져오기가 파싱한 섹션을 계속 추가할 수 있고, 리더는 곧 그릴 섹션만
 * 정확히 요청할 수 있습니다. `startOffset`/`endOffset`은 섹션을 전체 문서의 평면 텍스트 안에 배치하므로 검색
 * 결과, 북마크, 독서 위치를 섹션 간에 비교할 수 있습니다.
 *
 * 테이블 이름에는 역사가 있습니다. 검색 인덱스로 시작해 문서 저장소가 되었습니다. 섹션의 텍스트, 블록 구조,
 * 한 행에 저장하는 책 제목과 내비게이션까지 섹션에 관한 모든 정보가 여기에 있으므로 책을 열 때 쿼리 한 번이면 됩니다.
 *
 * @property documentId 이 섹션이 속한 문서이며 기본 키의 절반입니다.
 * @property sectionIndex 문서 순서에서 섹션의 위치이며 키의 나머지 절반입니다.
 * @property sectionTitle 섹션 제목이며 없으면 NULL입니다. 가져오기의 마지막 배치에서 책 내비게이션의 값으로
 * 교체합니다.
 * @property text 파싱할 때 줄 끝을 정규화한 섹션 텍스트입니다.
 * @property startOffset 전체 문서에서 이 텍스트가 시작하는 위치입니다.
 * @property endOffset 텍스트가 끝나는 위치 바로 다음이며 점진적 가져오기가 재개하는 위치입니다.
 * @property blocksJson 섹션의 블록 구조이며 큰 책에서 다른 모든 열보다 훨씬 큰 열입니다.
 * `getSectionBlocksJson`을 통해서만 읽습니다.
 * @property documentTitle 책 자체의 제목입니다. 문서를 열 때 텍스트와 함께 읽도록 별도 테이블 대신 한 섹션 행에
 * 기록합니다.
 * @property navigationJson 책의 목차이며 같은 방식과 같은 이유로 저장합니다.
 */
@Entity(
    tableName = "search_index",
    primaryKeys = ["documentId", "sectionIndex"],
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
data class SearchIndexEntity(
    val documentId: String,
    val sectionIndex: Int,
    val sectionTitle: String? = null,
    val text: String,
    val startOffset: Long,
    val endOffset: Long,
    val blocksJson: String = "[]",
    val documentTitle: String? = null,
    val navigationJson: String = "",
    /**
     * 이 행을 작성한 파서 빌드이며, 리더가 파서 변경보다 앞선 저장 텍스트를 구분해 다시 파싱할 수 있게 합니다.
     *
     * 숫자를 사용한 이유는 대안이 이전 코드의 흔적을 찾기 위해 블록 자체를 검사하는 것이기 때문입니다. 첫 삽화가
     * 292장에 있는 책에서는 질문 하나를 위해 열 때마다 293개 챕터를 디코딩해야 했습니다.
     */
    @ColumnInfo(defaultValue = "0")
    val parserVersion: Int = 0,
    /**
     * 이 섹션을 파싱한 spine 항목의 아카이브 상대 경로입니다. `finishEpubImport`가 저장된 모든 섹션의 전체
     * 텍스트를 다시 읽지 않고 내비게이션 제목과 원본 경로를 해석할 수 있도록 가져오는 동안 저장합니다. EPUB이
     * 아닌 문서나 TeddReaderMigration8To9 이전 레거시 행에서는 NULL입니다.
     */
    val sourcePath: String? = null,
)
