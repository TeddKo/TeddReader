package com.tedd.teddreader.app.reader.importer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidExternalDocumentIntentTest {
    @Test
    fun viewActionReturnsDataUri() {
        assertEquals(
            "content://docs/book.pdf",
            externalDocumentUriString(
                action = "android.intent.action.VIEW",
                dataUri = "content://docs/book.pdf",
                streamUri = null,
            ),
        )
    }

    @Test
    fun sendActionReturnsStreamUriOnly() {
        assertEquals(
            "content://docs/book.txt",
            externalDocumentUriString(
                action = "android.intent.action.SEND",
                dataUri = null,
                streamUri = "content://docs/book.txt",
            ),
        )
    }

    /** 외부 앱이 전달한 네트워크 URI는 앱이 직접 가져오지 않는지 검증한다. */
    @Test
    fun viewActionRejectsNetworkUri() {
        assertNull(
            externalDocumentUriString(
                action = "android.intent.action.VIEW",
                dataUri = "https://example.com/book.pdf",
                streamUri = null,
            ),
        )
    }

    /** 외부 앱이 전달한 file URI는 앱 내부 경로 접근에 악용되지 않도록 거부하는지 검증한다. */
    @Test
    fun sendActionRejectsFileUri() {
        assertNull(
            externalDocumentUriString(
                action = "android.intent.action.SEND",
                dataUri = null,
                streamUri = "file:///data/user/0/com.tedd.teddreader/files/private.txt",
            ),
        )
    }

    /** 실제 스트림이 상한과 같으면 전체 내용을 손실 없이 복사하는지 검증한다. */
    @Test
    fun boundedCopyAcceptsExactLimit() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()

        val copied = copyDocumentWithLimit(
            input = ByteArrayInputStream(bytes),
            output = output,
            maximumBytes = bytes.size.toLong(),
            displayName = "exact.txt",
        )

        assertEquals(bytes.size.toLong(), copied)
        assertContentEquals(bytes, output.toByteArray())
    }

    /** provider 보고와 달리 실제 스트림이 상한을 넘으면 즉시 실패하는지 검증한다. */
    @Test
    fun boundedCopyRejectsActualOversize() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        assertFailsWith<IllegalStateException> {
            copyDocumentWithLimit(
                input = ByteArrayInputStream(bytes),
                output = ByteArrayOutputStream(),
                maximumBytes = bytes.size.toLong() - 1L,
                displayName = "oversize.txt",
            )
        }
    }

    /** 지속 권한이 없는 provider의 SecurityException은 materialize fallback 신호가 되는지 검증한다. */
    @Test
    fun persistablePermissionSecurityFailureUsesFallback() {
        assertFalse(tryTakePersistableReadPermission { throw SecurityException("temporary grant") })
    }

    /** 지속 권한 처리의 예상 밖 오류는 숨기지 않고 호출자에게 전파하는지 검증한다. */
    @Test
    fun persistablePermissionUnexpectedFailureIsNotSwallowed() {
        assertFailsWith<IllegalStateException> {
            tryTakePersistableReadPermission { throw IllegalStateException("provider failure") }
        }
    }

    /** 지속 권한 획득 성공은 원본 URI를 계속 사용할 수 있음을 반환하는지 검증한다. */
    @Test
    fun persistablePermissionSuccessKeepsSourceUri() {
        assertTrue(tryTakePersistableReadPermission {})
    }

    @Test
    fun sendActionWithoutStreamIsIgnored() {
        assertNull(
            externalDocumentUriString(
                action = "android.intent.action.SEND",
                dataUri = null,
                streamUri = null,
            ),
        )
    }
}
