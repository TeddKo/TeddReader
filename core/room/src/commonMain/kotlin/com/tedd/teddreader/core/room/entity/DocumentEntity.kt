package com.tedd.teddreader.core.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 가져온 문서 하나를 나타내며 다른 모든 테이블이 캐스케이드되는 부모인 라이브러리 행입니다.
 *
 * 원본 URI(`id`)가 식별자이므로 같은 파일을 앱에 두 번 전달해도 두 번째 사본을 가져오지 않고 이 행으로
 * 해석합니다. 책을 완전히 파싱하기 전에는 개수를 알 수 없으므로 nullable이며, null인 [characterCount]로
 * 라이브러리가 완료되지 않은 가져오기를 인식합니다.
 *
 * 폴더 이름 없이 폴더만 지정한 행은 라이브러리에서 빈 칩으로 렌더링되므로 호출자에게 맡기지 않고 `init`에서
 * 폴더 소속을 검증합니다.
 *
 * @property id 문서를 가져온 원본 URI이자 다른 모든 테이블이 캐스케이드되는 키입니다.
 * @property name 라이브러리에 표시하는 문서 이름입니다.
 * @property sourceUri 파일이 있는 위치입니다. 현재는 [id]와 같지만 앱이 문서 키를 변경할 경우 식별자와 위치를
 * 분리할 수 있도록 별도로 유지합니다.
 * @property format 문서 형식의 이름입니다. 이후 빌드가 쓴 알 수 없는 값도 읽기 실패 없이 그대로 넘어가도록
 * 텍스트로 저장합니다.
 * @property mimeType 플랫폼이 보고한 값이며, 선택기가 아무 값도 제공하지 않았으면 NULL입니다.
 * @property sizeBytes 보고된 파일 크기이며, 알 수 없으면 NULL입니다.
 * @property addedAtEpochMillis 문서를 가져온 시각입니다.
 * @property lastOpenedAtEpochMillis 마지막으로 연 시각이며, 연 적이 없으면 NULL입니다. 그래서 라이브러리 정렬은
 * 이 값을 [addedAtEpochMillis]와 COALESCE 처리합니다.
 * @property pageCount 마지막으로 측정한 페이지 수이며, 측정한 적이 없으면 NULL입니다.
 * @property characterCount 텍스트의 문자 수이며, 가져오기가 끝나지 않았으면 NULL입니다.
 * @property wordCount 텍스트의 단어 수이며, 같은 이유로 NULL일 수 있습니다.
 * @property isBookmarked 라이브러리에서 책을 즐겨찾기에 넣었는지 나타냅니다.
 * @property folderId 책이 속한 폴더이며, 분류하지 않았으면 NULL입니다.
 * @property folderName 해당 폴더 이름이며 [folderId]가 있을 때만 존재합니다.
 * @property embeddedFontHrefsJson 문서가 참조하는 EPUB 글꼴 href의 JSON 인코딩된 정렬 집합입니다. 가져오는 동안
 * 파싱된 블록의 `fontHref` 필드에서 누적합니다. EPUB이 아닌 문서나 `TeddReaderMigration8To9` 이전에 기록한
 * 행에서는 NULL이며, 이 경우 첫 읽기 때 레거시 스캔으로 채웁니다. 인덱싱된 값을 사용하므로
 * [getReferencedEmbeddedFontHrefs]는 모든 섹션의 `blocksJson`을 디코딩하지 않고 글꼴 개수에 대해 O(F)로
 * 응답할 수 있습니다.
 * @throws IllegalArgumentException 폴더 쌍 중 하나만 채웠거나 어느 한쪽이 비어 있을 때 발생합니다.
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sourceUri: String,
    val format: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val addedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long? = null,
    val pageCount: Int? = null,
    val characterCount: Long? = null,
    val wordCount: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val isBookmarked: Boolean = false,
    val folderId: String? = null,
    val folderName: String? = null,
    /**
     * 이 행을 만든 가져오기가 완료된 시각이며, 완료된 적이 없으면 NULL입니다. 앱이 가져오기 도중 종료된 후에도
     * 절반만 가져온 책을 인식할 수 있게 합니다.
     *
     * 점진적 가져오기를 제공할 때 두 번째 스키마 버전 증가가 필요 없도록 이를 읽는 코드보다 먼저
     * TeddReaderMigration7To8에서 추가했습니다. 이미 존재하던 행은 정의상 가져오기가 완료되었으므로 각 행의
     * `addedAtEpochMillis`로 백필했습니다.
     */
    val importCompletedAtEpochMillis: Long? = null,
    /**
     * 이 문서가 참조하는 모든 내장 글꼴 href의 JSON 인코딩된 정렬 집합입니다. 가져오는 동안 파싱된
     * `ReaderBlock.style.fontHref`와 `spans.styleDelta.fontHref`의 합집합으로 점진적으로 누적합니다. EPUB이
     * 아닌 문서나 v9 스키마 이전 레거시 행에서는 NULL입니다. 첫 글꼴 href 쿼리 때 레거시 스캔으로 채우며,
     * 이후 읽기에서는 비용이 큰 전체 블록 사전 준비를 완전히 건너뜁니다.
     *
     * TeddReaderMigration8To9에서 추가했습니다.
     */
    val embeddedFontHrefsJson: String? = null,
) {
    init {
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
    }
}
