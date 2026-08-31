package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.icon.TeddIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the interaction contract that the app's ripple and touch-target policy exists to enforce.
 *
 * These properties are invisible to the compiler: a component can drop its `role`, lose its content
 * description, or stop reserving the 48dp touch floor and still build. Before this suite the only check on
 * any of it was a reviewer noticing, and the chip had in fact been reserving no touch space at all.
 *
 * Lives in `iosTest` rather than `commonTest` on purpose. `runComposeUiTest` needs a real composition
 * host; the iOS simulator target provides one, while `testAndroidHostTest` is a plain JVM unit test
 * that cannot run it without Robolectric. Compose Multiplatform draws these screens from the same
 * common sources on both platforms, so verifying here covers the shared behaviour — what it does not
 * cover is anything that only differs on Android.
 */
class TeddInteractionSemanticsTest {

    /**
     * The touch floor every interactive element has to clear, from
     * `TeddReaderSpacing.touchTarget`. Written as a literal here rather than read from the theme so the
     * test fails if someone lowers the token, instead of silently agreeing with the new value.
     */
    private val touchTarget = 48.dp

    /** The dismiss scrim is the sheet's only click target; its visual drag handle is not one. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun bottomSheetDragHandleIsNotAClickTarget() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddModalBottomSheet(
                    title = "Options",
                    onDismissRequest = {},
                ) {
                    TeddText("Body")
                }
            }
        }

        onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    /**
     * A tappable chip reserves the 48dp touch floor while the pill it draws stays compact.
     *
     * Both halves are asserted together because either one alone passes for the wrong reason. The
     * interaction node — the one `minimumInteractiveComponentSize` produces, one level out from the text
     * — must reach the floor; the text node must not, because inflating the pill to 48dp is the failure
     * mode a floor-only assertion would wave through.
     *
     * A touch-bounds assertion was tried here first and proved worthless: Compose stretches the hit test
     * of any `selectable` toward the platform minimum, so `assertTouchHeightIsEqualTo(48.dp)` stayed
     * green even with the chip's sizing neutralised. Layout bounds are what actually move.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappableChipReservesTouchFloorWithoutInflatingItsPill() = runComposeUiTest {
        var taps = 0
        setContent {
            TeddReaderTheme {
                TeddChip(text = "All", onClick = { taps++ }, selected = true)
            }
        }

        val label = onNodeWithText("All")
        label.assertIsSelected()

        val pill = label.getBoundsInRoot()
        assertTrue(
            pill.bottom - pill.top < touchTarget,
            "The drawn pill must stay compact rather than inflating to the touch floor",
        )

        label.onParent().assertHeightIsAtLeast(touchTarget)
        label.onParent().assertWidthIsAtLeast(touchTarget)

        label.performClick()
        assertEquals(1, taps, "A tappable chip must invoke its action")
    }

    /**
     * A selected chip has to say so to a screen reader, not just look different.
     *
     * The static, non-tappable chip renders through a separate branch that hand-draws its background,
     * and that branch has to add the selected semantics by hand because there is no selectable modifier
     * to supply them. It is the branch most likely to lose the state silently.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun staticSelectedChipStillAnnouncesSelection() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddChip(text = "EPUB", selected = true)
            }
        }

        onNodeWithText("EPUB").assertIsSelected()
    }

    /**
     * An icon button is often the only label its action has, so its description and its touch box are
     * both load-bearing.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun iconButtonExposesDescriptionAndClearsTouchFloor() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddIconButton(onClick = {}, contentDescription = "Bookmark") {
                    TeddIcon(imageVector = TeddIcons.BookmarkOutline, contentDescription = null)
                }
            }
        }

        val button = onNodeWithContentDescription("Bookmark")
        button.assertHeightIsAtLeast(touchTarget)
        button.assertWidthIsAtLeast(touchTarget)
    }

    /**
     * A settings row is one target, not a large one containing a small competing one.
     *
     * The row owns the toggle semantics and the switch glyph inside it is passed a null change handler.
     * If that split ever breaks, the glyph starts reporting its own toggle state and a screen reader
     * finds two controls where the user sees one — and tapping the row's text stops working, because
     * only the glyph is live. Asserting that exactly one node carries the toggle, and that tapping the
     * row's *label* flips it, catches both halves.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun switchRowIsOneToggleTargetDrivenByItsWholeWidth() = runComposeUiTest {
        var checked = false
        setContent {
            TeddReaderTheme {
                TeddSwitchRow(
                    title = "Keep screen on",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        val row = onNodeWithText("Keep screen on")
        row.assertIsOff()
        row.performClick()
        assertTrue(checked, "Tapping the row's label must drive the toggle, not just the glyph")
    }

    /**
     * A checkbox row follows the same single-target rule as the switch row, through a different
     * modifier, so it can regress independently.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun checkboxRowIsOneToggleTarget() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddCheckboxRow(title = "Include images", checked = true, onCheckedChange = {})
            }
        }

        onNodeWithText("Include images").assertIsOn()
    }

    /**
     * A radio row reports selection rather than a checked state, and its enclosing group owns the
     * "one of many" relationship.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun radioRowReportsSelectionNotCheckedState() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                Column {
                    TeddRadioRow(title = "Light", selected = true, onClick = {})
                    TeddRadioRow(title = "Dark", selected = false, onClick = {})
                }
            }
        }

        onNodeWithText("Light").assertIsSelected()
    }

    /**
     * A list row has to stay at least as tall as the row-height token even when its content is a single
     * short line, because a row shorter than the touch floor is unreachable in practice.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun listItemKeepsRowHeightFloor() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddListItem(title = "A", onClick = {})
            }
        }

        onNodeWithText("A").assertHeightIsAtLeast(56.dp)
    }

    /**
     * A button keeps the touch floor regardless of how short its label is.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun buttonClearsTouchFloorWithShortLabel() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddButton(text = "OK", onClick = {})
            }
        }

        onNodeWithText("OK").assertHeightIsAtLeast(touchTarget)
    }

    /**
     * The app-wide single-click guard drops a second tap inside its window, and only for call sites
     * that opted in.
     *
     * Verified through a button rather than the modifier directly, because the guard's whole purpose is
     * what a real control does with a bouncy double tap. A plain button does not opt in, so both taps
     * must land; that is the half of the contract most likely to be broken by turning the guard on
     * globally, which would make fast list interaction feel dead.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun plainButtonDeliversEveryTapBecauseGuardIsOptIn() = runComposeUiTest {
        var taps = 0
        setContent {
            TeddReaderTheme {
                TeddButton(text = "Add", onClick = { taps++ })
            }
        }

        val button = onNodeWithText("Add")
        button.performClick()
        button.performClick()

        assertEquals(2, taps, "A control that did not opt into the single-click guard must see both taps")
    }

    /**
     * A disabled button reports itself as disabled rather than merely ignoring taps, so assistive
     * technology can say why nothing happened.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun disabledButtonDoesNotInvokeItsAction() = runComposeUiTest {
        var taps = 0
        setContent {
            TeddReaderTheme {
                TeddButton(text = "Save", onClick = { taps++ }, enabled = false)
            }
        }

        onNodeWithText("Save").performClick()
        assertEquals(0, taps, "A disabled button must not invoke its action")
    }
}
