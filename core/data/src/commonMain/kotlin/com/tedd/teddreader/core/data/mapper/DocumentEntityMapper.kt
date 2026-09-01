package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.room.entity.DocumentEntity

/**
 * 라이브러리 행을 도메인 자체의 메타데이터 타입으로 읽어들인다.
 *
 * 포맷은 이름으로 매칭되며, 이 빌드가 모르는 값은 [DocumentFormat.UNKNOWN]으로 대체된다: 더
 * 새로운 버전이 쓴 행도 라이브러리를 깨뜨리지 않고 계속 목록에 나와야 한다. 저장된 크기가
 * null이면 0이 되는데, [DocumentLocation]은 크기를 선택적 사실이 아니라 숫자로 취급하기
 * 때문이다.
 *
 * `importCompletedAtEpochMillis`가 null이면 — 임포트가 아직 끝나지 않았다는 뜻 — 엔티티가
 * 진행 중인 누적값을 갖고 있어도 도메인 모델에서는 문자 수와 단어 수가 null로 가려진다. 이는
 * 기존 도메인 계약을 지킨다: null 카운트는 "아직 알 수 없음"을 의미하며, 통계를 표시하는
 * 호출자(문서 정보 시트)는 그에 맞게 처리한다.
 *
 * @receiver 저장된 행.
 * @return 도메인이 보는 것과 같은 문서.
 */
fun DocumentEntity.toDocumentMetadata(): DocumentMetadata {
    val importComplete = importCompletedAtEpochMillis != null
    return DocumentMetadata(
        id = DocumentId(id),
        location = DocumentLocation(
            sourceUri = sourceUri,
            displayName = name,
            mimeType = mimeType,
            sizeBytes = sizeBytes ?: 0L,
        ),
        format = DocumentFormat.entries.firstOrNull { it.name == format } ?: DocumentFormat.UNKNOWN,
        addedAtEpochMillis = addedAtEpochMillis,
        lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
        pageCount = pageCount,
        characterCount = if (importComplete) characterCount else null,
        wordCount = if (importComplete) wordCount else null,
        isBookmarked = isBookmarked,
        folderId = folderId,
        folderName = folderName,
    )
}

/**
 * 도메인 메타데이터를 라이브러리 행으로 다시 써넣는다.
 *
 * [toDocumentMetadata]의 역함수이지만 `importCompletedAtEpochMillis`는 예외인데, 이는 어떤
 * 도메인 타입도 갖고 있지 않다: 저장소가 그 컬럼을 소유하므로, 여기서의 쓰기는 그것을 기본값에
 * 남겨두며 임포트 도중인 문서를 갱신하는 데 쓰여서는 안 된다.
 *
 * @receiver 저장할 메타데이터.
 * @return upsert할 행.
 */
fun DocumentMetadata.toDocumentEntity(): DocumentEntity = DocumentEntity(
    id = id.value,
    name = location.displayName,
    sourceUri = location.sourceUri,
    format = format.name,
    mimeType = location.mimeType,
    sizeBytes = location.sizeBytes,
    addedAtEpochMillis = addedAtEpochMillis,
    lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
    pageCount = pageCount,
    characterCount = characterCount,
    wordCount = wordCount,
    isBookmarked = isBookmarked,
    folderId = folderId,
    folderName = folderName,
)
