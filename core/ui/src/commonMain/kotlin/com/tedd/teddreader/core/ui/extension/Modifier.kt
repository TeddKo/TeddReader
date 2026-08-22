package com.tedd.teddreader.core.ui.extension

import androidx.compose.foundation.Indication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Minimum gap enforced between two accepted taps by [singleClickable]. 300ms sits comfortably above
 * a real double-tap (which fires within the platform's own ~40-300ms double-tap window depending on
 * device) while staying short enough that a deliberate second, separate tap is never dropped.
 */
private val SingleClickInterval = 300.milliseconds

/**
 * The [NestedScrollConnection] behind [consumeUnconsumedVerticalScroll]: it reports back as consumed
 * every unit of vertical scroll or fling velocity a descendant did not already use, while leaving
 * horizontal motion untouched. Declared once at file scope so every use of
 * [consumeUnconsumedVerticalScroll] shares the same stateless instance instead of allocating one per
 * call site.
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
 * Swallows any vertical scroll or fling delta that this node's own scrollable descendants leave
 * unconsumed, instead of letting it bubble up to whatever nested-scroll consumer sits further out.
 * `TeddModalBottomSheet` applies this to stop leftover scroll from a content list that has hit its
 * top or bottom from being reinterpreted by the sheet's own drag handling as a request to expand or
 * dismiss the sheet.
 *
 * @receiver The [Modifier] to attach the swallowing behavior to.
 * @return The receiver with [consumeUnconsumedVerticalScrollConnection] installed via `nestedScroll`.
 */
fun Modifier.consumeUnconsumedVerticalScroll(): Modifier =
    nestedScroll(consumeUnconsumedVerticalScrollConnection)

/**
 * Restricts this node to tracking a single active pointer at a time, consuming the touch events of
 * any additional pointer that goes down while the first is still pressed. This mirrors Android's
 * `android:splitMotionEvents="false"`: without it, two fingers landing on the same composable can
 * each independently drive whatever gesture detector sits above this modifier (for example firing a
 * click twice, once per finger). No call site currently applies this modifier in this codebase; it
 * exists as an available building block for a region that turns out to need single-touch semantics.
 *
 * @receiver The [Modifier] to restrict to one active pointer.
 * @return The receiver with the single-pointer tracking installed via `pointerInput`.
 */
fun Modifier.disableSplitMotionEvents(): Modifier = pointerInput(Unit) {
    handlePointerEvents()
}

/**
 * The pointer loop behind [disableSplitMotionEvents]: reads every pointer event at the
 * [PointerEventPass.Initial] pass — before children can react to it — and hands each batch of
 * changes to a fresh [PointerTracker] so only the pointer that pressed down first is ever allowed
 * through.
 *
 * @receiver The [PointerInputScope] supplying the pointer event stream to filter.
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
 * Tracks which single pointer, by id, is allowed to drive a gesture, for [handlePointerEvents]. The
 * first pointer to go down while none is tracked becomes the tracked one; once it lifts, tracking
 * resets so the next pointer down claims it. Any other pointer's changes are consumed for as long as
 * a different pointer is already tracked, which is what actually blocks a second finger from being
 * seen by gesture detectors above this node.
 */
private class PointerTracker {
    /** Id of the pointer currently allowed through, or -1 when no pointer is tracked. */
    private var currentId: Long = -1L

    /**
     * Consumes every change in [changes] that does not belong to the currently tracked pointer,
     * updating which pointer is tracked as pointers press and release.
     *
     * @param changes The pointer changes from one event, as delivered by [handlePointerEvents].
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

/**
 * [clickable] with debouncing built in: a second tap within [SingleClickInterval] of an
 * accepted one is swallowed instead of invoking [onClick] again. Exists for actions where a
 * double-invocation is actually harmful (e.g. a navigation or a one-shot mutation firing twice from
 * a bouncy touch or an accidental double-tap), rather than merely a visual double-ripple. No call
 * site currently applies this modifier in this codebase; it exists as an available building block for
 * an action that turns out to need this protection.
 *
 * @receiver The [Modifier] to attach the debounced click handling to.
 * @param enabled Whether the modifier responds to taps at all.
 * @param onClickLabel Accessibility label describing the click action.
 * @param interactionSource Interaction source reported to [clickable]; when null, one is remembered
 * internally.
 * @param indication Visual feedback shown on press; defaults to the platform ripple.
 * @param role Accessibility role reported for this element.
 * @param onClick Invoked at most once per [SingleClickInterval] window.
 * @return The receiver wrapped in a debounced [clickable].
 */
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
