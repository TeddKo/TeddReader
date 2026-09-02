package com.tedd.teddreader.core.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

/**
 * 한 문서에서 정확한 (글꼴 크기, 줄 높이, 글꼴 패밀리, 뷰포트) 조합으로 측정한 페이지 시작점입니다. 이 값들만
 * 페이지 분할 엔진이 페이지를 나누는 위치를 결정합니다.
 *
 * [fontFamilyName]에는 `""`를 저장합니다. 이는 `ReaderStyle.fontFamilyName`이 `null`인 경우, 즉 시스템 기본
 * 글꼴을 뜻합니다. 여기서는 `NULL` 열에 그 의미를 담을 수 없습니다. 아래 열들이 정확히 이 테이블의 기본 키이고
 * SQLite는 각 `NULL`을 `PRIMARY KEY`/`UNIQUE` 인덱스에서 다른 모든 `NULL`과 서로 다른 값으로 취급합니다. 따라서
 * "명시적 패밀리 없음" 스타일로 측정한 두 행이 충돌하지 않아 동일 레이아웃을 두 번 업서트하면 첫 행을 교체하지
 * 않고 두 번째 행을 삽입하게 됩니다. 빈 문자열은 유효한 글꼴 패밀리 이름이 아니므로 실제 이름과 충돌하지 않습니다.
 *
 * [pageStartsBlob]이 `ByteArray`이므로 데이터 클래스가 생성하는 `equals`/`hashCode`는 이 프로퍼티에 대해
 * 내용이 아니라 참조를 비교합니다. 내용 비교가 필요해지면 `contentEquals`/`contentHashCode`를 쓰는
 * `equals`/`hashCode`를 직접 재정의해야 합니다.
 *
 * @property documentId 이 페이지 시작점을 측정한 책입니다.
 * @property fontSizeSp 측정할 때 사용한 글자 크기입니다.
 * @property lineHeightMultiplier 측정할 때 사용한 줄 높이입니다.
 * @property fontFamilyName 측정할 때 사용한 패밀리이며, `""`는 시스템 기본값을 뜻합니다.
 * @property viewportWidthPx 측정 영역의 너비입니다.
 * @property viewportHeightPx 측정 영역의 높이입니다.
 */
@Entity(
    tableName = "page_layouts",
    primaryKeys = [
        "documentId",
        "fontSizeSp",
        "lineHeightMultiplier",
        "fontFamilyName",
        "viewportWidthPx",
        "viewportHeightPx",
    ],
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
data class PageLayoutEntity(
    val documentId: String,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val fontFamilyName: String,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    /**
     * 이 측정값을 만들 때 문서의 문자 수이며, 행을 계속 신뢰할 수 있는지 결정하는 지문입니다.
     *
     * 문서를 다시 파싱하면 모든 오프셋이 이동할 수 있으므로 이전 텍스트에서 측정한 페이지 시작점은 독자를 잘못된
     * 구절로 이동시킵니다. 숫자 하나의 비교는 매번 열 때 수행해도 충분히 저렴하며, 일치하지 않으면 행을 신뢰하지
     * 않고 버립니다.
     */
    val characterCount: Long,
    /**
     * 현재 [pageStartsBlob]이 담는 동일한 오프셋의 레거시 저장소입니다. `NOT NULL`이고 열을 삭제하려면 전체
     * 테이블을 다시 작성해야 하므로 유지합니다.
     *
     * 이 Long JSON 배열의 디코딩은 큰 책 복원 비용의 대부분이었습니다. 책을 열 때마다 ~110 KB 분량의 숫자를
     * 파싱했으며 그 책은 16,734페이지였습니다. 새 행은 더 이상 여기에 실제 배열을 인코딩하지 않습니다.
     */
    val pageStartsJson: String = "[]",
    /**
     * 각 본문 페이지가 시작하는 위치를 페이지마다 하나의 절대 문서 오프셋으로 오름차순 저장하며 표지 페이지는
     * 제외합니다. 표지는 언제나 정확히 첫 섹션이므로 측정값 없이 다시 만들 수 있습니다.
     *
     * JSON 숫자 대신 오프셋마다 little-endian Int32를 사용합니다. 오프셋은 `Int`에 충분히 들어가며, 이 리더가
     * 여는 가장 큰 실제 책은 3.5M자입니다. 따라서 16,734페이지 책은 ~67 KB로, ~110 KB였던 JSON보다 작고
     * 디코딩도 JSON 파싱 대신 바이트를 순회하는 방식입니다.
     *
     * 이 열을 추가한 마이그레이션은 백필할 수 없었으므로 nullable입니다. 대신 TeddReaderMigration7To8이
     * 해당 행을 삭제하며, 데이터 손실 없이 한 번 재측정하는 비용만 듭니다.
     */
    val pageStartsBlob: ByteArray? = null,
    /** 이 행을 측정한 시각이며, 최신순 해석과 테이블 크기 제한의 정렬 기준입니다. */
    val writtenAtEpochMillis: Long,
    /**
     * 이 행을 전체 문서가 아닌 불완전한 가져오기 앞부분에서 측정했는지 나타냅니다. 부분 행은 문서의 현재
     * `characterCount`가 이 행 자체의 [characterCount]와 여전히 일치할 때만 신뢰합니다. 문서가 늘어나는 순간,
     * 즉 새 가져오기 배치가 저장되면 이전 앞부분의 부분 행은 오래된 값이므로 삭제하거나 승격해야 합니다.
     * 가져오기가 완료된 뒤 만든 행은 `isPartial = false`로 기록하며 여러 세션에 걸쳐 유지됩니다.
     *
     * 새 스키마와 TeddReaderMigration8To9 모두 기본값으로 `0` (false)을 사용하므로 기존의 완전한 행은 백필
     * 없이도 유효합니다.
     */
    @ColumnInfo(defaultValue = "0")
    val isPartial: Boolean = false,
)
