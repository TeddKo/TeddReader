package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentLocation
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [AndroidPdfMetadataReader]가 접근 불가능한 입력에는 안전한 기본값을 사용하면서도, 예상된 PDF 실패보다
 * 심각한 메모리 고갈과 코루틴 취소를 숨기지 않는 예외 경계를 검증한다. Android host 환경은
 * `ParcelFileDescriptor`를 구현하지 않으므로 실제 렌더링 대신 플랫폼 호출 전후의 순수 계약을 고정한다.
 */
class AndroidPdfMetadataReaderTest {
    /** 위치와 바이트가 모두 접근 불가능하면 페이지 수 기본값 1이 반환된다. */
    @Test
    fun pageCountReturnsOneWhenFileIsMissingAndBytesNull() {
        val location = missingPdfLocation()

        assertEquals(1, AndroidPdfMetadataReader().pageCount(location, bytes = null))
    }

    /** 위치와 바이트가 모두 접근 불가능하면 표지 대신 null이 반환된다. */
    @Test
    fun coverImageBytesReturnsNullWhenLocationUnreachableAndBytesNull() {
        val location = missingPdfLocation()

        assertNull(AndroidPdfMetadataReader().coverImageBytes(location, bytes = null))
    }

    /** PDF I/O 실패는 문서별 실패로 축소되어 null 결과가 된다. */
    @Test
    fun expectedPdfIoFailureReturnsNull() {
        assertNull(androidPdfResultOrNull<Unit> { throw IOException("broken PDF") })
    }

    /** 메모리 고갈은 손상 PDF처럼 숨기지 않고 호출자까지 전파된다. */
    @Test
    fun outOfMemoryErrorIsRethrown() {
        assertFailsWith<OutOfMemoryError> {
            androidPdfResultOrNull<Unit> { throw OutOfMemoryError("exhausted") }
        }
    }

    /** 코루틴 취소는 렌더러 상태 실패로 오인하지 않고 호출자까지 전파된다. */
    @Test
    fun cancellationIsRethrown() {
        assertFailsWith<CancellationException> {
            androidPdfResultOrNull<Unit> { throw CancellationException("cancelled") }
        }
    }

    /**
     * 플랫폼 API를 호출하기 전에 접근 불가능하다고 판정되는 고정 PDF 위치를 만든다.
     *
     * @return 파일이 존재하지 않고 바이트 크기가 0인 문서 위치.
     */
    private fun missingPdfLocation(): DocumentLocation = DocumentLocation(
        sourceUri = "file:///nonexistent/path/to/missing.pdf",
        displayName = "missing.pdf",
        mimeType = "application/pdf",
        sizeBytes = 0L,
    )
}
