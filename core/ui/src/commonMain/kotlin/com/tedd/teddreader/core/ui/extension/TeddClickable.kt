package com.tedd.teddreader.core.ui.extension

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The window within which a second accepted tap is treated as an unintended repeat.
 *
 * 300ms sits above a real double-tap (the platform's own double-tap window runs to roughly 300ms)
 * while staying short enough that a deliberate, separate second tap is never swallowed.
 */
private val SingleClickInterval = 300.milliseconds

/**
 * When the last tap accepted by a `singleClick = true` call site happened, shared by every such call
 * site in the app.
 *
 * Deliberately app-wide rather than per-call-site. The bug this exists to prevent is *double
 * navigation* — two different list rows tapped in the same frame each pushing a destination — and a
 * timestamp remembered per row cannot see the other row's tap. A single shared mark is the only thing
 * that can.
 *
 * No lock guards it, and none is needed: Compose delivers click callbacks on the composition's single
 * applier thread, so there is never concurrent access to serialize. A mutex here would cost
 * synchronization on every tap to protect against a race that the threading model already excludes,
 * and `kotlin.synchronized` is not available in `commonMain` anyway.
 */
private var lastAcceptedClickMark: TimeMark? = null

/**
 * Decides whether a tap arriving now should be delivered, given the app-wide single-click guard.
 *
 * @return true when the tap is accepted, which also records it as the new reference point; false when
 * it falls inside [SingleClickInterval] of the previously accepted tap and must be dropped.
 */
private fun acceptSingleClick(): Boolean {
    val mark = lastAcceptedClickMark
    if (mark != null && mark.elapsedNow() < SingleClickInterval) return false
    lastAcceptedClickMark = TimeSource.Monotonic.markNow()
    return true
}

/**
 * The app's single entry point for making something tappable, and the reason ripple feedback looks the
 * same everywhere.
 *
 * Every interactive surface in the app goes through this modifier rather than calling `clickable`
 * directly, because the ripple contract is only enforceable if there is one place that applies it. That
 * contract is: **the ripple covers a component's internal padding and never its external spacing.**
 * Concretely, the modifier chain a caller writes is
 *
 * 1. the size or fill boundary,
 * 2. this modifier, which clips to [shape] before attaching the indication,
 * 3. `padding` for the component's own content inset,
 * 4. the child layout.
 *
 * Because `padding` comes after this modifier, the padded area is inside the ripple; because the clip
 * happens inside this modifier, the ripple can never bleed past the visible shape. A `padding` placed
 * *before* this modifier would shave the touch and ripple region and is the one ordering mistake this
 * API exists to make impossible to write by accident.
 *
 * Minimum touch target is deliberately *not* handled here. It belongs to step 1, the boundary: a
 * full-width row grows with `heightIn(min = rowHeight)`, while a compact control expands its hit area
 * without enlarging the visible indication. Enforcing 48dp inside this modifier would inflate the
 * ripple of every small control along with its touch area.
 *
 * @receiver the modifier chain to attach the click handling to, already carrying its size boundary.
 * @param onClick invoked when the element is tapped.
 * @param shape the outline the ripple is clipped to. Pass null for an element that already sits inside
 * a clipped parent or that genuinely fills a rectangular region, such as a full-width list row —
 * passing a shape there would round the ripple against a square row.
 * @param enabled whether the element responds to input at all; false also removes the click semantics
 * rather than merely ignoring taps.
 * @param role the accessibility role announced for this element.
 * @param onClickLabel accessibility description of what the tap does.
 * @param onLongClick invoked on long press; when non-null the element uses `combinedClickable` so a
 * long press and a tap stay distinguishable. Null means long presses pass through to whatever gesture
 * handler sits above, which is what a row inside a pannable surface wants.
 * @param onLongClickLabel accessibility description of the long press, meaningful only alongside
 * [onLongClick].
 * @param singleClick whether this call site joins the app-wide guard described on
 * [lastAcceptedClickMark]. Turn it on for navigation and for one-shot mutations, where a duplicate
 * invocation is actually harmful. Leave it off for anything a user may legitimately tap in quick
 * succession — toggling several list selections, stepping a value — because the guard is shared and
 * would swallow the second of two intentional taps on *different* elements.
 * @param interactionSource the source to report presses to; pass one when the component reflects its
 * own pressed state in its colours, so the visual and the indication observe the same stream. When
 * null, one is remembered internally.
 * @return the receiver with clip, indication and click handling applied in the contracted order.
 */
@Composable
fun Modifier.teddClickable(
    onClick: () -> Unit,
    shape: Shape? = null,
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    singleClick: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
): Modifier = teddInteraction(
    shape = shape,
    interactionSource = interactionSource,
    rippleEnabled = true,
) { source, indication ->
    val guardedClick: () -> Unit = { if (!singleClick || acceptSingleClick()) onClick() }

    if (onLongClick == null) {
        clickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            interactionSource = source,
            indication = indication,
            onClick = guardedClick,
        )
    } else {
        combinedClickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            interactionSource = source,
            indication = indication,
            onClick = guardedClick,
        )
    }
}

/**
 * The on/off counterpart of [teddClickable], carrying the same ripple and ordering contract.
 *
 * Used by a settings row whose entire width toggles a value. The row owns the toggle semantics and the
 * visual control inside it is passed a null change handler, so the row is one accessibility target
 * instead of a large one containing a small competing one.
 *
 * @receiver the modifier chain to attach the toggle handling to, already carrying its size boundary.
 * @param value the current state, reported to accessibility services as the checked state.
 * @param onValueChange invoked with the state the element should move to.
 * @param shape the outline the ripple is clipped to; null for a full-width row.
 * @param enabled whether the element responds to input.
 * @param role the accessibility role, typically `Role.Switch` or `Role.Checkbox` — this is what tells
 * a screen reader which kind of control the row is.
 * @param interactionSource the source to report presses to; when null, one is remembered internally.
 * @return the receiver with clip, indication and toggle handling applied in the contracted order.
 */
@Composable
fun Modifier.teddToggleable(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    shape: Shape? = null,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier = teddInteraction(
    shape = shape,
    interactionSource = interactionSource,
    rippleEnabled = true,
) { source, indication ->
    toggleable(
        value = value,
        enabled = enabled,
        role = role,
        interactionSource = source,
        indication = indication,
        onValueChange = onValueChange,
    )
}

/**
 * The mutually-exclusive-choice counterpart of [teddClickable], carrying the same ripple and ordering
 * contract.
 *
 * Used by a radio row and by a selectable chip. This modifier itself only ever reports its own
 * selected state; the set-relationship semantics — a screen reader announcing "2 of 3" rather than
 * just "selected" — are added only when the caller wraps the rows in
 * [com.tedd.teddreader.core.ui.component.TeddOptionGroup] with `isSelectableGroup = true`, which is
 * what actually applies `Modifier.selectableGroup` to the enclosing column. Left unwrapped, or
 * wrapped without that flag, each row still announces whether it is selected, but a screen reader has
 * no way to say how many choices exist or which position this one occupies.
 *
 * @receiver the modifier chain to attach the selection handling to, already carrying its size boundary.
 * @param selected whether this element is the chosen one, reported as selected semantics.
 * @param onClick invoked when the element is chosen. Selecting an already-selected element still
 * fires, because a caller may legitimately treat that as a re-confirmation.
 * @param shape the outline the ripple is clipped to; null for a full-width row, a pill for a chip.
 * @param enabled whether the element responds to input.
 * @param role the accessibility role announced for this element.
 * @param interactionSource the source to report presses to; when null, one is remembered internally.
 * @return the receiver with clip, indication and selection handling applied in the contracted order.
 */
@Composable
fun Modifier.teddSelectable(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape? = null,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier = teddInteraction(
    shape = shape,
    interactionSource = interactionSource,
    rippleEnabled = true,
) { source, indication ->
    selectable(
        selected = selected,
        enabled = enabled,
        role = role,
        interactionSource = source,
        indication = indication,
        onClick = onClick,
    )
}

/**
 * Resolves the interaction source and indication once, clips to [shape], then hands both to whichever
 * foundation interaction modifier the caller needs.
 *
 * Exists so the clip-then-indicate ordering and the ripple colour live in exactly one place. If each
 * public modifier above repeated them, the contract would be four copies that can drift, which is the
 * situation this file was written to end.
 *
 * @receiver the modifier chain the interaction is being attached to.
 * @param shape the outline to clip to before attaching the indication, or null to leave the receiver's
 * own bounds as the ripple boundary.
 * @param interactionSource the caller's source, or null to remember one here.
 * @param rippleEnabled whether to supply a ripple indication or none at all.
 * @param attach builds the actual interaction modifier from the resolved source and indication.
 * @return the receiver, clipped if asked, with [attach]'s modifier applied on top.
 */
@Composable
private inline fun Modifier.teddInteraction(
    shape: Shape?,
    interactionSource: MutableInteractionSource?,
    rippleEnabled: Boolean,
    attach: Modifier.(MutableInteractionSource, androidx.compose.foundation.Indication?) -> Modifier,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val indication = if (rippleEnabled) ripple(color = teddReaderColors().ripple) else null
    val clipped = if (shape != null) clip(shape) else this
    return clipped.attach(source, indication)
}

/**
 * Draws the app's container treatment — depth, border, fill, and a clip that holds children inside the
 * corner — as modifiers on a layout that already exists.
 *
 * Preferred over wrapping content in a card composable whenever the content already has a layout root
 * of its own, because that wrapper would add a node that does nothing but carry these four modifiers.
 * A grid cell, an overlay `Box`, or a pager page gets the card look from here; only a container that
 * genuinely needs to arrange children reaches for a card composable.
 *
 * The trailing clip is the part that is easy to leave out and costly to miss: without it a child image
 * or a filled row draws over the rounded corner the border just described, and the corner looks broken
 * only for that one child.
 *
 * Fill goes on before the border, not after. In a modifier chain the earlier entry is the outer one and
 * is painted first, so a border declared ahead of the fill gets painted over by it — a 1dp stroke sits
 * on the shape's edge, so half of it disappears under the fill and the outline reads as a hairline that
 * renders inconsistently rather than as a border. The reference implementation this was ported from
 * ordered them the other way round; that ordering is the one thing not carried over.
 *
 * @receiver the modifier chain of the layout being given the container treatment.
 * @param shape the container outline, used by all four layers so the depth, border, fill and clip agree.
 * @param elevation the shadow depth; pass `0.dp` for a flat container, which skips no work but produces
 * no visible shadow.
 * @param borderWidth the border thickness; pass `0.dp` for no border.
 * @param borderColor the border colour, defaulting to the palette's subtle container outline.
 * @param backgroundColor the fill, defaulting to the palette's surface.
 * @return the receiver with shadow, border, background and clip applied in that order.
 */
@Composable
fun Modifier.teddSurface(
    shape: Shape = teddReaderShapes().medium,
    elevation: Dp = 0.dp,
    borderWidth: Dp = 1.dp,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
): Modifier {
    val colors = teddReaderColors()

    return this
        .shadow(elevation = elevation, shape = shape, spotColor = colors.shadow, ambientColor = colors.shadow)
        .background(color = backgroundColor ?: colors.surface, shape = shape)
        .border(BorderStroke(borderWidth, borderColor ?: colors.outlineSubtle), shape)
        .clip(shape)
}

/**
 * Hands focus back from a text field when the user taps the surrounding background.
 *
 * Applied to a screen's background layer or a form's root. Without it a soft keyboard raised by a
 * search or note field stays up until the user finds the system back gesture, because tapping empty
 * space is not something a `TextField` hears.
 *
 * This installs a tap detector, so it must not be placed over a region that owns its own gestures — a
 * reader page in particular, where it would compete with page navigation for the same tap.
 *
 * @receiver the modifier chain of the background layer.
 * @param focusManager the manager whose focus is cleared, normally `LocalFocusManager.current`.
 * @param enabled whether to install the detector at all; false returns the receiver untouched so a
 * screen can disable the behaviour without restructuring its modifier chain.
 * @param force whether to clear focus even when the focused field captured focus explicitly; true also
 * releases focus a field is holding deliberately.
 * @return the receiver, with the background tap detector installed when [enabled].
 */
fun Modifier.clearFocusOnBackgroundTap(
    focusManager: FocusManager,
    enabled: Boolean = true,
    force: Boolean = false,
): Modifier {
    if (!enabled) return this

    return pointerInput(focusManager, force) {
        detectTapGestures(onTap = { focusManager.clearFocus(force = force) })
    }
}
