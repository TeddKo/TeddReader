package com.tedd.teddreader.core.ui.extension

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val SingleClickInterval = 300.milliseconds

fun Modifier.disableSplitMotionEvents(): Modifier = pointerInput(Unit) {
    handlePointerEvents()
}

private suspend fun PointerInputScope.handlePointerEvents() = coroutineScope {
    val tracker = PointerTracker()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            tracker.process(event.changes)
        }
    }
}

private class PointerTracker {
    private var currentId: Long = -1L

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

fun Modifier.singleClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
    role: Role? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    var lastClickMark: TimeMark? = remember { null }

    clickable(
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        interactionSource = source,
        indication = indication ?: ripple(),
    ) {
        val mark = lastClickMark
        if (mark == null || mark.elapsedNow() >= SingleClickInterval) {
            lastClickMark = TimeSource.Monotonic.markNow()
            onClick()
        }
    }
}
