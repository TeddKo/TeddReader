package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import org.koin.core.annotation.Single

/**
 * 일반 텍스트 파일을 전체 텍스트에 걸친 섹션 하나만 가진 [ReaderDocument]로 바꾼다.
 *
 * `.txt` 파일은 아무 구조도 갖지 않는다 — 챕터도, 헤딩도, 마크업도 없다 — 그래서 EPUB이나
 * 코믹 파서와 달리 여기서 감지하거나 나눌 것이 아무것도 없다: 파일 전체가 단일 [ReaderSection]이
 * 된다. 줄바꿈은 다른 무엇보다 먼저 `\n`으로 정규화된다. Windows나 구식 Mac에서 작성된 파일에
 * 남은 `\r\n`이나 홀로 있는 `\r`을 그대로 두면 이후 읽기 위치, 검색 결과, [TextRange]가 의존하는
 * 모든 문자 오프셋이 어긋나기 때문이다.
 */
@Single
class TxtDocumentParser {
    /**
     * [text]를 한 섹션짜리 문서로 감싸며, 먼저 줄바꿈을 정규화한다.
     *
     * @param id 원본 파일의 식별자. 그대로 전달된다.
     * @param title 문서와 그 단일 섹션 모두에 쓰이는 레이블.
     * @param text 파일의 전체 디코딩된 내용; 원본 바이트가 이 문자열로 어떻게 디코딩되었는지(인코딩
     *   감지, BOM 처리)는 이 호출 이전에 일어나며 여기서 일어나지 않는다.
     * @return [DocumentFormat.TXT]의 [ReaderDocument]로, [text] 전체를 담은 [ReaderSection] 하나를
     *   가지며, 줄바꿈은 `\n`으로 정규화되어 있다.
     */
    fun parse(
        id: DocumentId,
        title: String,
        text: String,
    ): ReaderDocument {
        val normalizedText = text.replace("\r\n", "\n").replace('\r', '\n')
        return ReaderDocument(
            id = id,
            format = DocumentFormat.TXT,
            title = title,
            sections = listOf(
                ReaderSection(
                    index = 0,
                    title = title,
                    text = normalizedText,
                    range = TextRange(0L, normalizedText.length.toLong()),
                ),
            ),
        )
    }
}
