package com.tedd.teddreader.core.common

import kotlin.coroutines.cancellation.CancellationException

/**
 * 코루틴 내부에서 [runCatching]을 대신해 취소를 안전하게 처리한다.
 *
 * 표준 라이브러리의 [runCatching]은 [CancellationException]을 포함한 모든 [Throwable]을 잡는다. 취소를 삼키면 구조화된 동시성이 조용히 깨진다. 중지 지시를 받은 코루틴이 계속 실행되고, 부모 스코프는 자식이 끝났다는 사실을 알지 못하며, 하위 수집기나 `join()` 호출이 멈춘다. 이 함수는 실패를 [Result]로 감싸기 전에 모든 [CancellationException]을 다시 던져 올바른 전파를 복원한다.
 *
 * 람다가 일시 중단 호출을 수행하거나 전이적으로 수행할 수 있으면 [runCatching] 대신 이 함수를 사용한다. 모든 오류를 의도적으로 삼키는 일시 중단 없는 최선 노력 블록(예: 절대 일시 중단하지 않는 실행 후 결과를 기다리지 않는 캐시 쓰기)은 일반 [runCatching]을 계속 사용해야 한다.
 *
 * 이 함수는 `inline`이므로 런타임 오버헤드가 없으며, 특히 함수 자체를 `suspend`로 선언하지 않아도 [block] 안의 일시 중단 호출 지점이 컴파일된다. 이는 표준 라이브러리 [runCatching]이 모든 호출 지점에서 예외를 던지는 람다를 받는 것과 같은 메커니즘이다.
 *
 * @param T [block]이 생성하는 성공 값의 타입.
 * @param block 시도할 작업으로, 일시 중단하거나 예외를 던질 수 있다.
 * @return [block]의 반환 값을 감싼 [Result.success], 또는 [block]이 던진 취소 이외의 예외를 감싼 [Result.failure].
 * @throws CancellationException [block]이 이를 던진 경우. 구조화된 동시성을 보존하기 위해 항상 다시 던진다.
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
