package com.tedd.teddreader.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * A cancellation-safe replacement for [runCatching] inside coroutines.
 *
 * The stdlib [runCatching] catches every [Throwable], including [CancellationException].
 * Swallowing a cancellation silently breaks structured concurrency: a coroutine that was told to
 * stop keeps running, parent scopes never learn the child finished, and downstream collectors or
 * `join()` calls hang. This function restores correct propagation by rethrowing any
 * [CancellationException] before wrapping the failure in a [Result].
 *
 * Use this function in place of [runCatching] whenever the lambda performs — or may transitively
 * perform — a suspending call. Non-suspend best-effort blocks that intentionally swallow all
 * errors (e.g. fire-and-forget cache writes that never suspend) should keep using plain
 * [runCatching].
 *
 * The function is `inline` so it imposes no runtime overhead and, critically, so that a suspend
 * call site inside [block] compiles without requiring the function itself to be declared
 * `suspend` — the same mechanism the stdlib [runCatching] uses to accept throwing lambdas at any
 * call site.
 *
 * @param T the success type the [block] produces.
 * @param block the operation to attempt; may suspend and may throw.
 * @return [Result.success] wrapping [block]'s return value, or [Result.failure] wrapping any
 *   non-cancellation exception [block] threw.
 * @throws CancellationException if [block] threw one, always rethrown to preserve structured
 *   concurrency.
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> suspendRunCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }
}
