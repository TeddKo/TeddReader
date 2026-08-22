package com.tedd.teddreader.core.ui.extension

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies [consumeUnconsumedVerticalScrollConnection]'s post-scroll/post-fling behavior directly,
 * without going through Compose — this is the piece `TeddModalBottomSheet` relies on to stop leftover
 * vertical drag from leaking into the sheet's own drag-to-dismiss handling, so its exact
 * consumed/available split needs to be pinned by a test rather than only exercised indirectly.
 */
class ModifierTest {
    /**
     * Confirms `onPostScroll` reports the entire available vertical delta as consumed while leaving
     * horizontal delta alone, and that `onPreScroll` (not overridden) consumes nothing.
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
     * Confirms `onPostFling` reports the entire available vertical fling velocity as consumed while
     * leaving horizontal velocity alone, and that `onPreFling` (not overridden) consumes nothing.
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
