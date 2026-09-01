package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation

/**
 * 이 리더가 PDF의 구조에서 필요로 하는 것 — 페이지 수와 커버 썸네일 — 을 공용 코틀린 PDF
 * 라이브러리 없이 읽어낸다. 두 플랫폼 모두 이를 처리할 수 있는 자체 네이티브 PDF 프레임워크
 * (Android의 `PdfRenderer`, iOS의 PDFKit)를 갖고 있으므로, 각 [defaultPdfMetadataReader] 구현은
 * 대신 각 플랫폼의 프레임워크를 감싼다.
 *
 * 계약은 **위치 우선(location-first)** 이다: 구현체는 [DocumentLocation.sourceUri]가 접근
 * 가능한 로컬 파일 경로를 가리키는 경우 항상 그것으로부터 문서를 해석하며, 위치를 직접 열 수 없을
 * 때(구체화(materialize)되지 않은 Android `content://` URI, 또는 플랫폼 프레임워크가 도달할 수
 * 없는 위치)만 원본 [bytes]로 폴백한다. 이렇게 하면 이미 디스크에 있는 PDF를 메타데이터를 읽거나
 * 커버를 렌더링하기 위해서만 임시 파일로 다시 구체화하는 일을 피할 수 있다 — 이는 예전에 이
 * 인터페이스가 무조건 bytes를 요구함으로써 강제했던 불필요한 I/O 경로였다.
 *
 * 호출자는 [DocumentLocation.sourceUri]가 읽을 수 있는 로컬 파일(iOS의 샌드박스 복사본, 또는
 * 구체화 이후 Android의 `file://` URI)을 가리킨다는 것을 알고 있을 때 `bytes = null`을 전달할 수
 * 있다. [bytes]가 null인데 위치에 도달할 수 없는 것으로 판명되면, 구현체는 예외를 던지는 대신
 * 안전한 기본값([pageCount]는 1, [coverImageBytes]는 null)을 반환한다.
 */
fun interface PdfMetadataReader {
    /**
     * 문서의 페이지 수.
     *
     * 구현체는 먼저 [DocumentLocation.sourceUri]를 로컬 파일로 시도한다; 그 경로를 직접 열 수
     * 없을 때만 [bytes]를 임시 파일에 써서 폴백한다.
     *
     * @param location 문서의 위치. [DocumentLocation.sourceUri]가 PDF를 해석하는 1차 소스이다.
     * @param bytes [location]을 직접 열 수 없을 때의 폴백으로 쓰이는 문서의 원본 바이트, 또는
     *   호출자가 [location]이 접근 가능한 로컬 파일임을 보장할 때는 `null`.
     * @return 페이지 수. 구현체는 절대 예외를 던지지 않으며 1 미만을 반환하지 않는다; PDF의 실제
     *   구조를 읽는 데 실패하면 `1`로 폴백한다 — 이 리더가 이미 받아들인 문서는 보여줄 페이지가
     *   최소 하나는 있어야 하기 때문이다.
     */
    fun pageCount(location: DocumentLocation, bytes: ByteArray?): Int

    /**
     * 문서 첫 페이지의 썸네일. 매번 완전한 PDF 렌더러를 여는 일 없이 책장에 보여주기 위한 것.
     *
     * 구현체는 먼저 [DocumentLocation.sourceUri]를 로컬 파일로 시도한다; 그 경로를 직접 열 수
     * 없을 때만 [bytes]를 임시 파일에 써서 폴백한다.
     *
     * @param location 문서의 위치. [DocumentLocation.sourceUri]가 PDF를 해석하는 1차 소스이다.
     * @param bytes [location]을 직접 열 수 없을 때의 폴백으로 쓰이는 문서의 원본 바이트, 또는
     *   호출자가 [location]이 접근 가능한 로컬 파일임을 보장할 때는 `null`.
     * @return 작은 표시 영역에 맞게 축소된 썸네일의 PNG 인코딩 바이트, 또는 문서에 렌더링할
     *   페이지가 없거나 렌더링이 실패하면 `null`. 기본 구현은 `null`이며, 이는 [pageCount]만
     *   필요로 하는 향후의 이 인터페이스 호출자를 위한 것이다.
     */
    fun coverImageBytes(location: DocumentLocation, bytes: ByteArray?): ByteArray? = null
}

/** 플랫폼별 [PdfMetadataReader] — Android는 `PdfRenderer`를, iOS는 PDFKit을 감싼다. */
internal expect fun defaultPdfMetadataReader(): PdfMetadataReader
