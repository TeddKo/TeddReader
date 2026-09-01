package com.tedd.teddreader.core.ui.extension

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope

/**
 * [consumeUnconsumedVerticalScroll] 뒤에 있는 [NestedScrollConnection]: 자손이 아직 사용하지 않은
 * 모든 수직 스크롤 또는 플링 속도 단위를 소비된 것으로 보고하고, 수평 움직임은 그대로 둔다.
 * [consumeUnconsumedVerticalScroll]의 모든 사용처가 호출부마다 인스턴스를 할당하는 대신 같은 상태
 * 없는 인스턴스를 공유하도록 파일 스코프에서 한 번만 선언되어 있다.
 */
internal val consumeUnconsumedVerticalScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(x = 0f, y = available.y)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(x = 0f, y = available.y)
}

/**
 * 이 노드 자신의 스크롤 가능한 자손들이 소비하지 않고 남긴 수직 스크롤 또는 플링 델타를, 더 바깥에
 * 있는 어떤 nested-scroll 소비자로 버블링하게 두는 대신 삼킨다. `TeddModalBottomSheet`는 위나 아래
 * 끝에 도달한 콘텐츠 목록에서 남은 스크롤이 시트 자체의 드래그 처리에 의해 시트를 펼치거나 닫으라는
 * 요청으로 재해석되는 것을 막기 위해 이를 적용한다.
 *
 * @receiver 삼키는 동작을 붙일 [Modifier].
 * @return `nestedScroll`을 통해 [consumeUnconsumedVerticalScrollConnection]이 설치된 리시버.
 */
fun Modifier.consumeUnconsumedVerticalScroll(): Modifier =
    nestedScroll(consumeUnconsumedVerticalScrollConnection)

/**
 * 이 노드가 한 번에 하나의 활성 포인터만 추적하도록 제한하고, 첫 번째 포인터가 아직 눌려 있는 동안
 * 눌리는 추가 포인터의 터치 이벤트를 소비한다. 이는 Android의
 * `android:splitMotionEvents="false"`를 그대로 반영한다: 이것이 없으면 같은 컴포저블 위에 놓인 두
 * 손가락이 각각 독립적으로 이 modifier 위에 있는 어떤 제스처 감지기든 구동할 수 있다(예를 들어
 * 손가락마다 한 번씩 클릭을 두 번 발생시킴). 이 코드베이스에는 현재 이 modifier를 적용하는 호출부가
 * 없다. 이는 단일 터치 semantics가 필요한 것으로 밝혀지는 영역을 위해 사용 가능한 빌딩 블록으로
 * 존재한다.
 *
 * 리더의 페이지 트리 안 어디에도 적용해서는 안 된다. 리더 자체의 멀티터치 처리는 핀치줌이 페이지
 * 내비게이션보다 우선하도록 [PointerEventPass.Initial] 패스를 차지하는데, 이 modifier도 같은 패스에서
 * 소비한다 — 둘은 두 번째 손가락을 두고 충돌할 것이고, 핀치는 페이지에 도달하지 못하게 될 것이다.
 *
 * @receiver 활성 포인터 하나로 제한할 [Modifier].
 * @return `pointerInput`을 통해 단일 포인터 추적이 설치된 리시버.
 */
fun Modifier.disableSplitMotionEvents(): Modifier = pointerInput(Unit) {
    handlePointerEvents()
}

/**
 * [disableSplitMotionEvents] 뒤에 있는 포인터 루프: 자식이 반응하기 전인
 * [PointerEventPass.Initial] 패스에서 모든 포인터 이벤트를 읽고, 각 변경 배치를 새 [PointerTracker]에
 * 넘겨 가장 먼저 눌린 포인터만 통과되도록 한다.
 *
 * @receiver 필터링할 포인터 이벤트 스트림을 공급하는 [PointerInputScope].
 */
private suspend fun PointerInputScope.handlePointerEvents() = coroutineScope {
    val tracker = PointerTracker()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            tracker.process(event.changes)
        }
    }
}

/**
 * [handlePointerEvents]를 위해, id로 어떤 단일 포인터가 제스처를 구동하도록 허용되는지 추적한다.
 * 추적 중인 포인터가 없는 동안 가장 먼저 눌린 포인터가 추적 대상이 된다. 그것이 떼어지면 추적이
 * 초기화되어 다음에 눌리는 포인터가 그 자리를 차지한다. 이미 다른 포인터가 추적되고 있는 동안 그
 * 밖의 포인터 변경은 모두 소비되는데, 이것이 바로 이 노드 위의 제스처 감지기가 두 번째 손가락을 보지
 * 못하도록 실제로 막는 부분이다.
 */
private class PointerTracker {
    /** 현재 통과가 허용된 포인터의 id, 추적 중인 포인터가 없으면 -1. */
    private var currentId: Long = -1L

    /**
     * [changes] 중 현재 추적 중인 포인터에 속하지 않는 모든 변경을 소비하고, 포인터가 눌리고 떼어짐에
     * 따라 어떤 포인터가 추적되는지 갱신한다.
     *
     * @param changes [handlePointerEvents]가 전달하는, 한 이벤트에서 나온 포인터 변경들.
     */
    fun process(changes: List<PointerInputChange>) {
        changes.forEach { change ->
            when {
                change.pressed && currentId == -1L -> currentId = change.id.value
                !change.pressed && currentId == change.id.value -> currentId = -1L
                change.id.value != currentId -> change.consume()
            }
        }
    }
}
