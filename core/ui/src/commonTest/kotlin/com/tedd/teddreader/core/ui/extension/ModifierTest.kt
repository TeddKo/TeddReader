package com.tedd.teddreader.core.ui.extension

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose를 거치지 않고 [consumeUnconsumedVerticalScrollConnection]의 post-scroll/post-fling 동작을
 * 직접 검증한다 — 이것은 `TeddModalBottomSheet`가 남은 수직 드래그가 시트 자체의 드래그-닫기 처리로
 * 새어 들어가는 것을 막기 위해 의존하는 부분으로, 그 정확한 consumed/available 분할은 간접적으로만
 * 검증되는 대신 테스트로 고정되어야 한다.
 */
class ModifierTest {
    /**
     * `onPostScroll`이 수평 델타는 그대로 두고 사용 가능한 수직 델타 전체를 소비된 것으로 보고하며,
     * (오버라이드되지 않은) `onPreScroll`은 아무것도 소비하지 않음을 확인한다.
     */
    @Test
    fun connectionConsumesOnlyUnconsumedVerticalPostScroll() {
        val available = Offset(x = 12f, y = -24f)

        assertEquals(
            Offset(x = 0f, y = available.y),
            consumeUnconsumedVerticalScrollConnection.onPostScroll(
                consumed = Offset.Zero,
                available = available,
                source = NestedScrollSource.UserInput,
            ),
        )
        assertEquals(
            Offset.Zero,
            consumeUnconsumedVerticalScrollConnection.onPreScroll(
                available = available,
                source = NestedScrollSource.UserInput,
            ),
        )
    }

    /**
     * `onPostFling`이 수평 속도는 그대로 두고 사용 가능한 수직 플링 속도 전체를 소비된 것으로 보고하며,
     * (오버라이드되지 않은) `onPreFling`은 아무것도 소비하지 않음을 확인한다.
     */
    @Test
    fun connectionConsumesOnlyUnconsumedVerticalPostFling() = runTest {
        val available = Velocity(x = 12f, y = -24f)

        assertEquals(
            Velocity(x = 0f, y = available.y),
            consumeUnconsumedVerticalScrollConnection.onPostFling(
                consumed = Velocity.Zero,
                available = available,
            ),
        )
        assertEquals(
            Velocity.Zero,
            consumeUnconsumedVerticalScrollConnection.onPreFling(available),
        )
    }
}
