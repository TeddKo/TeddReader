package com.tedd.teddreader.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [suspendRunCatching]의 계약을 고정한다. 취소 이외의 예외는 [Result.failure]에 담고 성공은 [Result.success]에 담으며, [CancellationException]은 절대 감싸지 않고 항상 다시 던진다. 각 테스트는 변이에 민감하다. 다시 던지는 동작, `catch`, 성공 경로 중 하나라도 제거하면 하나 이상의 테스트가 실패한다.
 */
class SuspendRunCatchingTest {

    /**
     * 블록 안에서 던진 [CancellationException]은 [Result]에 담지 않고 [suspendRunCatching] 밖으로 같은 인스턴스가 전파돼야 한다. 명시적으로 다시 던지는 동작이 없으면 예외가 [Result.failure]에 감싸지므로 이 테스트가 실패한다.
     */
    @Test
    fun cancellationExceptionIsRethrown() = runTest {
        assertFailsWith<CancellationException> {
            suspendRunCatching {
                throw CancellationException("scope cancelled")
            }
        }
    }

    /**
     * [CancellationException]의 하위 클래스도 다시 던져야 한다. 구조화된 동시성은 예외 계층에 의존하며 사용자 정의 취소 원인도 여전히 취소이기 때문이다.
     */
    @Test
    fun cancellationExceptionSubclassIsRethrown() = runTest {
        class CustomCancellation : CancellationException("custom")

        assertFailsWith<CustomCancellation> {
            suspendRunCatching {
                throw CustomCancellation()
            }
        }
    }

    /**
     * 취소 이외의 예외(여기서는 [IllegalStateException])는 전파하지 않고 [Result.failure]에 담아야 한다. 일반 `catch` 절이 없으면 이 테스트가 실패한다.
     */
    @Test
    fun nonCancellationExceptionIsCapturedInResult() = runTest {
        val result = suspendRunCatching {
            throw IllegalStateException("boom")
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    /**
     * 성공한 블록의 반환 값은 [Result.success]로 감싸야 한다. 성공 경로가 없으면 이 테스트가 실패한다.
     */
    @Test
    fun successfulBlockReturnsResultSuccess() = runTest {
        val result = suspendRunCatching { 42 }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    /**
     * 실제 일시 중단 호출과 함께 함수가 올바르게 동작하는지 검증한다. 함수가 인라인이고 호출 위치가 코루틴 안에 있으므로 람다는 일시 중단 함수를 호출할 수 있어야 한다. 내부 일시 중단 호출을 막는 시그니처 변경을 방지한다.
     */
    @Test
    fun suspendCallInsideBlockCompileAndExecutesCorrectly() = runTest {
        suspend fun fetchValue(): String = "fetched"

        val result = suspendRunCatching { fetchValue() }

        assertTrue(result.isSuccess)
        assertEquals("fetched", result.getOrNull())
    }
}
