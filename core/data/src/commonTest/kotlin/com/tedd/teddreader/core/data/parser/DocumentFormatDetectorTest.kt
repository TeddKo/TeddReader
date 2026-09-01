package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentLocation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [DocumentFormatDetector]가 소스로부터 받을 수 있는 모든 입력 — MIME 타입, 파일 확장자, 그리고
 * (최후 수단의 확인으로서) 파일 자체의 선행 바이트 — 에 걸친 우선순위 규칙을 고정한다. 의도적인
 * 제외 사항도 포함한다: 일반 `.zip`(또는 `.docx`/`.mobi` 같은 ZIP 기반 형식)은 CBZ와 컨테이너
 * 형식을 공유한다는 이유만으로 절대 만화로 읽혀서는 안 되고, 벡터 `image/svg+xml`은 절대 지원되는
 * 래스터 IMAGE로 읽혀서는 안 된다.
 */
class DocumentFormatDetectorTest {
    private val detector = DocumentFormatDetector()

    /** TXT는 파일 내용과 무관하게 MIME 타입 또는 `.txt` 확장자로 감지된다. */
    @Test
    fun detectsTxtByMimeAndExtension() {
        assertEquals(
            DocumentFormat.TXT,
            detector.detect(location("book.bin", mimeType = "text/plain"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.TXT,
            detector.detect(location("book.txt"), byteArrayOf(1)),
        )
    }

    /**
     * PDF는 MIME 타입, `%PDF` 바이트 시그니처, 또는 `.pdf` 확장자로 감지된다 — 이 중 하나만
     * 만족해도 충분하다.
     */
    @Test
    fun detectsPdfByMimeHeaderAndExtension() {
        assertEquals(
            DocumentFormat.PDF,
            detector.detect(location("book.bin", mimeType = "application/pdf"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.PDF,
            detector.detect(location("book.bin"), "%PDF-1.7".encodeToByteArray()),
        )
        assertEquals(
            DocumentFormat.PDF,
            detector.detect(location("book.pdf"), byteArrayOf(1)),
        )
    }

    /** EPUB는 두 가지 MIME 타입 표기 중 하나 또는 `.epub` 확장자로 감지된다. */
    @Test
    fun detectsEpubByMimeAndExtension() {
        assertEquals(
            DocumentFormat.EPUB,
            detector.detect(location("book.bin", mimeType = "application/epub"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.EPUB,
            detector.detect(location("book.bin", mimeType = "application/epub+zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.EPUB,
            detector.detect(location("book.epub"), byteArrayOf(1)),
        )
    }

    /**
     * 회귀 가드: CBZ는 만화 전용 MIME 타입 또는 `.cbz` 확장자로만 감지된다 — 이미 `.cbz`(대소문자
     * 무관)로 이름이 붙은 파일이라도 일반 `application/zip` MIME 타입만으로는 절대 감지되지 않으며,
     * 그 일반 MIME 타입을 가진 순수 `.zip` 확장자는 UNKNOWN으로 해석되어야 한다.
     */
    @Test
    fun detectsCbzByComicMimeAndExtensionWithoutAcceptingGenericZip() {
        assertEquals(
            DocumentFormat.CBZ,
            detector.detect(location("comic.bin", mimeType = "application/vnd.comicbook+zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.CBZ,
            detector.detect(location("comic.CBZ", mimeType = "application/zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("archive.zip", mimeType = "application/zip"), byteArrayOf(1)),
        )
    }

    /**
     * 래스터 이미지는 MIME 타입, 확장자(대소문자 무관), 또는 매직 바이트 시그니처로 감지된다;
     * SVG(`image/svg+xml`, 텍스트 형태의 `<svg` 콘텐츠)는 의도적으로 그중 하나가 아니며 UNKNOWN으로
     * 해석되어야 한다.
     */
    @Test
    fun detectsSupportedRasterImagesByMimeExtensionAndSignature() {
        assertEquals(
            DocumentFormat.IMAGE,
            detector.detect(location("page.bin", mimeType = "image/jpeg"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.IMAGE,
            detector.detect(location("page.WEBP"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.IMAGE,
            detector.detect(
                location("page.bin"),
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            ),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("vector.svg", mimeType = "image/svg+xml"), "<svg".encodeToByteArray()),
        )
    }

    /**
     * 순수 ZIP, Word `.docx`, Kindle `.mobi` — 이 리더가 파싱하지 않는 컨테이너/바이너리 형식들
     * — 은 지원되는 형식으로 오인되지 않고 UNKNOWN으로 해석되어야 한다.
     */
    @Test
    fun rejectsUnsupportedZipDocxAndMobi() {
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("archive.zip", mimeType = "application/zip"), byteArrayOf(1)),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(
                location(
                    "book.docx",
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ),
                byteArrayOf(1),
            ),
        )
        assertEquals(
            DocumentFormat.UNKNOWN,
            detector.detect(location("book.mobi", mimeType = "application/x-mobipocket-ebook"), byteArrayOf(1)),
        )
    }

    /**
     * 주어진 표시 이름과 선택적 MIME 타입을 가지며, 고정된 가짜 `file:///` URI를 사용하는
     * [DocumentLocation] 픽스처.
     */
    private fun location(
        name: String,
        mimeType: String? = null,
    ): DocumentLocation = DocumentLocation(
        sourceUri = "file:///$name",
        displayName = name,
        mimeType = mimeType,
    )
}
