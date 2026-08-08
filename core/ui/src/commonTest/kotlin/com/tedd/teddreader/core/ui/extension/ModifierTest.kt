package com.tedd.teddreader.core.ui.extension

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ModifierTest {
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
