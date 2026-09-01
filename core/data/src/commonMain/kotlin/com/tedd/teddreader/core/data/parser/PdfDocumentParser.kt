package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.DocumentLocation
import com.tedd.teddreader.core.common.model.ReaderDocument
import org.koin.core.annotation.Single

/**
 * PDF를 순전히 페이지 수만 있는 [ReaderDocument]로 연다: 텍스트도 추출되지 않고 [ReaderSection]도
 * 만들어지지 않는데, 이 리더가 PDF에 대해 보여주는 페이지는 리플로우된 텍스트가 아니라 페이지를
 * 렌더링한 이미지이기 때문이다. 실제 PDF 작업 — 페이지 수를 읽고, 플랫폼이 지원하면 커버
 * 썸네일을 렌더링하는 것 — 은 모두 [metadataReader]에 위임되며, 이것이 플랫폼별 실제 PDF
 * 엔진을 감싼다. 이 클래스는 그 플랫폼의 답을 앱의 나머지 부분이 기대하는 공통
 * [ReaderDocument]/커버 바이트 계약으로 형태만 바꿀 뿐이다.
 *
 * 이 API는 **위치 우선**이다: [bytes]는 처음부터 끝까지 nullable이며, PDF가 이미 디스크에
 * materialize되어 있는 호출자(임포트 후의 일반적인 경우)는 파일 전체를 메모리에 들고 있다가
 * 곧바로 임시 파일에 다시 쓸 플랫폼 리더에 넘기는 일을 피하기 위해 `null`을 넘긴다. [bytes]가
 * non-null이면 [DocumentLocation.sourceUri]를 직접 열 수 없는 플랫폼(예: materialize되지 않은
 * 안드로이드 `content://` URI)을 위한 대체 수단으로 쓰인다.
 *
 * @property metadataReader 플랫폼의 PDF 리더. 기본값은 [defaultPdfMetadataReader], 플랫폼별 실제
 *   구현을 연결하는 expect/actual 팩토리다; 테스트는 실제 PDF를 건드리지 않고 보고되는 페이지
 *   수와 커버 바이트를 제어하기 위해 여기에 페이크를 넘긴다.
 */
@Single
class PdfDocumentParser(
    private val metadataReader: PdfMetadataReader = defaultPdfMetadataReader(),
) {
    /**
     * [location]에 있는 PDF에 대해 페이지 수만 있는 문서를 만든다.
     *
     * @param id 원본 파일의 식별자, 변경 없이 그대로 전달됨.
     * @param title 문서에 표시될 레이블; 여기서는 PDF 자체의 메타데이터에서 도출되지 않는다.
     * @param location PDF가 어디서 왔는지; 플랫폼 리더가 파일을 열기 위해 필요한 주 핸들(경로
     *   또는 콘텐츠 URI)로서 [metadataReader]에 그대로 전달된다.
     * @param bytes [metadataReader]를 위한 대체 수단으로서의 PDF 원시 내용, 또는 호출자가
     *   [location]이 접근 가능한 로컬 파일임을 알고 있어 인메모리 대체 수단이 필요 없으면 `null`.
     * @return 섹션이 없고 [metadataReader]에서 가져온 페이지 수를 가진 [DocumentFormat.PDF]의
     *   [ReaderDocument], 하한을 1로 두어 페이지 수를 판별하지 못하는 리더 — 또는 잘못된
     *   PDF에 대해 0이나 음수를 보고하는 경우 — 가 절대 열 수 없는 페이지 없는 문서를 만들지
     *   않게 한다.
     */
    fun parse(
        id: DocumentId,
        title: String,
        location: DocumentLocation,
        bytes: ByteArray? = null,
    ): ReaderDocument = ReaderDocument(
        id = id,
        format = DocumentFormat.PDF,
        title = title,
        sections = emptyList(),
        pageCount = metadataReader.pageCount(location, bytes).coerceAtLeast(1),
    )

    /**
     * PDF의 커버 썸네일, [metadataReader]에서 그대로 가져온 것.
     *
     * @param location PDF가 어디서 왔는지; [parse] 참고.
     * @param bytes 대체 수단으로서의 PDF 원시 내용, 또는 [location]이 접근 가능한 로컬 파일이면
     *   `null`; [parse] 참고.
     * @return [metadataReader]가 렌더링하는 커버 이미지 바이트, 또는 플랫폼 리더가 커버를
     *   지원하지 않거나(기본값은 null 반환) 이 파일에 대해 아무것도 만들 수 없으면 null.
     */
    fun coverImageBytes(
        location: DocumentLocation,
        bytes: ByteArray? = null,
    ): ByteArray? = metadataReader.coverImageBytes(location, bytes)
}
