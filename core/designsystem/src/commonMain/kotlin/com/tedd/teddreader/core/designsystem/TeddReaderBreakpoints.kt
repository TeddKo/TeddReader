package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The width thresholds that govern adaptive layout in the app.
 *
 * Each value is a content-width constraint, not a device model or orientation. The widths are
 * measured after safe-area insets are removed, so the same threshold applies whether the device
 * is held in portrait or landscape — orientation alone must never force a two-pane layout. A wide
 * device in portrait may still qualify for an expanded layout if its safe-inset-adjusted width
 * meets [expanded]; a narrow device in landscape does not qualify simply because it rotated.
 *
 * `@Immutable` because breakpoint values are read during composition and must not change without
 * invalidating their consumers, so Compose can treat the object as stable and skip re-composition
 * of consumers that received the same instance.
 *
 * @property compact Any content width below this value is the compact window class (240–359 dp).
 * Compact layouts use a single column and stack actions vertically.
 * @property medium Content widths from [compact] up to [expanded] form the medium window class.
 * The reader can switch to a two-page spread at this width, but only when each pane can satisfy
 * [minPaneWidth]; width alone is not sufficient.
 * @property expanded Content widths at or above this value are the expanded window class. Adaptive
 * grids and persistent navigation rails are appropriate here.
 * @property readableMaxWidth The maximum content width for single-column reading surfaces: search
 * results, detail screens, and forms. Constraining the column to this width keeps line length
 * between roughly 45 and 75 characters, which is the range where reading speed and comprehension
 * peak.
 * @property collectionMaxWidth The maximum content width for collection surfaces such as
 * grid-based library views. Wider than [readableMaxWidth] because a grid benefits from more
 * columns, while a prose column does not benefit from longer lines.
 * @property minPaneWidth The minimum width each pane must satisfy when the reader splits into two
 * panes. If the available content width cannot provide [minPaneWidth] to both panes simultaneously,
 * the layout must stay single-pane regardless of total width.
 * @property compactControlWidth The width threshold below which a single control's own internal
 * layout collapses to a stacked, single-column arrangement — the in-document search form stacking
 * its field above its button, or the saved-places empty state switching from centered to
 * start-aligned text. Distinct from [compact]: [compact] gates a screen-level window class, while
 * this value gates one control's own composition at a size the control decides for itself,
 * independent of the screen's overall window class. Kept as its own value rather than reusing
 * [compact] because the two thresholds are not interchangeable — substituting [compact] here would
 * shift the point at which these controls restack.
 */
@Immutable
data class TeddReaderBreakpoints(
    val compact: Dp = 360.dp,
    val medium: Dp = 600.dp,
    val expanded: Dp = 840.dp,
    val readableMaxWidth: Dp = 720.dp,
    val collectionMaxWidth: Dp = 960.dp,
    val minPaneWidth: Dp = 280.dp,
    val compactControlWidth: Dp = 320.dp,
)

/** The breakpoints the theme installs unless a caller overrides them. */
val DefaultTeddReaderBreakpoints = TeddReaderBreakpoints()
