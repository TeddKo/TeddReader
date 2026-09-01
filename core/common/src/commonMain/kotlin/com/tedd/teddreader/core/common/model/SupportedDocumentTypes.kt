package com.tedd.teddreader.core.common.model

/**
 * [DocumentFormat]이 이름 붙일 수 있는 형식이 아니라 이 리더가 실제로 열 수 있는 형식이다.
 *
 * 서로 다른 질문에 답하므로 열거형과 분리한다. 열거형은 앱이 분류한 형식([DocumentFormat.UNKNOWN] 포함)에 이름을 붙일 수 있어야 하지만, 이 집합은 가져오기 도구가 허용하고 폴더 검색이 필터링하는 대상이다.
 */
val SupportedDocumentFormats: Set<DocumentFormat> = setOf(
    DocumentFormat.TXT,
    DocumentFormat.PDF,
    DocumentFormat.EPUB,
    DocumentFormat.CBZ,
    DocumentFormat.IMAGE,
)

/**
 * 시스템 파일 브라우저가 이 리더로 읽을 수 없는 파일을 비활성화하도록 문서 선택기를 열 때 지정하는 MIME 타입이다.
 *
 * 형식마다 타입이 여러 개인 것은 의도적이다. EPUB은 파일을 만든 앱이나 다운로드 방식에 따라 `application/epub` 또는 `application/epub+zip`으로, 만화는 두 공급업체 타입 중 하나로 전달된다. 따라서 감지는 이 타입들을 힌트로만 사용하고 파일 이름으로 보완하므로, 타입이 없거나 잘못된 파일도 가져올 수 있다.
 */
val SupportedDocumentMimeTypes: Set<String> = setOf(
    "text/plain",
    "application/pdf",
    "application/epub",
    "application/epub+zip",
    "application/vnd.comicbook+zip",
    "application/x-cbz",
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/bmp",
)

/**
 * Google Drive 선택기에 요청하는 타입으로, 같은 집합에 `application/zip`을 더한 값이다.
 *
 * Drive는 CBZ를 일반 ZIP으로 저장하므로 만화 타입만 요청하는 선택기에서는 Drive의 만화가 보이지 않는다. 추가 타입은 Drive에만 한정한다. 이를 [SupportedDocumentMimeTypes]에 넣으면 기기의 모든 ZIP이 책인 것처럼 제시되기 때문이다.
 */
val GoogleDriveSupportedDocumentMimeTypes: Set<String> = SupportedDocumentMimeTypes + "application/zip"

/**
 * 문서의 MIME 타입이 없거나 잘못됐을 때 형식을 식별하는 파일 확장자이자, 폴더 가져오기가 디렉터리 트리에서 찾는 대상이다.
 */
val SupportedDocumentExtensions: Set<String> = setOf(
    "txt",
    "pdf",
    "epub",
    "cbz",
    "jpg",
    "jpeg",
    "png",
    "webp",
    "gif",
    "bmp",
)
