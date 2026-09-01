package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import org.koin.core.annotation.Single

/**
 * 단일 래스터 이미지 파일을 1페이지짜리 [ReaderDocument]로 변환해, 리더가 그림 한 장을 책과
 * 똑같은 방식으로 열 수 있게 한다 — [ReaderDocument]를 기대하는 모든 호출부에 특수 케이스를
 * 끼워넣는 대신, 페이지 수를 가진 무언가로 취급한다. 이미지 자체의 바이트는 전혀 검사하지 않는다;
 * "그림 한 장"이라는 사실 외에 파싱할 게 없으므로 [pageCount][ReaderDocument]는 항상 1이고
 * [sections][ReaderDocument]는 항상 비어 있다. 실제 픽셀 디코딩은 나중에, 페이지가 그려지는
 * 시점에 필요할 때 이루어진다.
 */
@Single
class ImageDocumentParser {
    /**
     * [id]로 식별되는 이미지에 대해 1페이지짜리 문서를 만든다.
     *
     * @param id 원본 이미지의 식별자. 그대로 전달되므로 호출자가 문서를 원본 파일로 다시 추적할 수 있다.
     * @param title 문서에 표시될 라벨; 이 파서는 이미지로부터 제목을 유추하지 않는다.
     * @return 섹션이 없고 페이지 수가 1인 [DocumentFormat.IMAGE] 형식의 [ReaderDocument].
     */
    fun parse(
        id: DocumentId,
        title: String,
    ): ReaderDocument = ReaderDocument(
        id = id,
        format = DocumentFormat.IMAGE,
        title = title,
        sections = emptyList(),
        pageCount = 1,
    )
}
