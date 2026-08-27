package com.tedd.teddreader.core.common

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the contract of [suspendRunCatching]: non-cancellation exceptions are captured in a
 * [Result.failure], successes are captured in a [Result.success], and [CancellationException] is
 * always rethrown — never wrapped. Each test is mutation-sensitive: removing the rethrow, removing
 * the catch, or removing the success path would fail at least one.
 */
class SuspendRunCatchingTest {

    /**
     * A [CancellationException] thrown inside the block must propagate out of
     * [suspendRunCatching] as-is, not be captured in a [Result]. Without the explicit rethrow
     * this test fails because the exception would be wrapped in [Result.failure].
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
     * A subclass of [CancellationException] must also be rethrown, because structured
     * concurrency relies on the exception hierarchy — a custom cancellation cause is still a
     * cancellation.
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
     * A non-cancellation exception (here [IllegalStateException]) must be captured in
     * [Result.failure] rather than propagating. Without the general catch clause this test
     * fails.
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
     * A successful block return must be wrapped in [Result.success]. Without the success path
     * this test fails.
     */
    @Test
    fun successfulBlockReturnsResultSuccess() = runTest {
        val result = suspendRunCatching { 42 }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    /**
     * Verifies the function works correctly with an actual suspending call — the lambda must be
     * able to call suspend functions because the function is inline and the call site is in a
     * coroutine. This guards against a signature change that would prevent suspend calls inside.
     */
    @Test
    fun suspendCallInsideBlockCompileAndExecutesCorrectly() = runTest {
        suspend fun fetchValue(): String = "fetched"

        val result = suspendRunCatching { fetchValue() }

        assertTrue(result.isSuccess)
        assertEquals("fetched", result.getOrNull())
    }
}
