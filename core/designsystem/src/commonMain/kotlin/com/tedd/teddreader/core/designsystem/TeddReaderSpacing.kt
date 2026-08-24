package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The gaps the app is built from, so a screen never writes a raw `dp` for spacing.
 *
 * Named by size for general use and by place for the three surfaces whose padding is a deliberate
 * decision, so changing what "a card's inside" means is one edit rather than a search for `16.dp`.
 *
 * `@Immutable` because these values are read during composition: without it Compose treats the whole
 * theme as unstable and re-composes every consumer whenever the theme object is passed again.
 *
 * @property none no gap, for a conditional that has to supply one anyway.
 * @property xxSmall the tightest gap, between glyph-level details.
 * @property xSmall same size as [xxSmall] today, kept as its own step so the two can diverge without
 * touching call sites.
 * @property small gap between related controls.
 * @property medium the default gap between elements of a screen.
 * @property large gap between groups.
 * @property xLarge gap between sections.
 * @property xxLarge gap around a section that stands alone.
 * @property xxxLarge the largest step, for empty states and hero areas.
 * @property screenPadding the inset from a screen's edges.
 * @property cardPadding the inset inside a library card.
 * @property sheetPadding the inset inside a bottom sheet, larger because a sheet is read closer.
 * @property readerMargin the margin around a reading page, which also bounds the text column pagination
 * measures.
 * @property touchTarget The minimum side length for any interactive element. This value implements
 * the accessibility floor — it must not shrink even when screen space is tight, because a smaller
 * touch target makes the control inaccessible rather than merely compact.
 * @property rowHeight The minimum height for a list row. Larger than [touchTarget] to accommodate
 * rows that carry two lines of text; the extra height comes from the text measurement, not
 * from arbitrary padding added on top.
 * @property sectionGap The gap between adjacent sections. Three distinct gap values exist because
 * the app previously used a single `large` (24 dp) for all three structural gaps, which made
 * section boundaries and item boundaries visually indistinguishable and collapsed the screen's
 * hierarchy into a flat list. Splitting them into [sectionGap], [sectionHeaderGap], and [itemGap]
 * lets the eye follow the structure.
 * @property sectionHeaderGap The gap between a section title and the content immediately below it.
 * Kept smaller than [sectionGap] so the title reads as belonging to its section rather than
 * floating ambiguously between two sections.
 * @property itemGap The gap between adjacent items within a single section's body. The tightest of
 * the three structural gaps, reinforcing that items are siblings inside one section while sections
 * are visually heavier breaks.
 * @property readerPageHorizontal The horizontal inset between a reading page's own edge and where
 * its rendered text begins. This is the reader text-page contract `DESIGN.md` specifies as
 * "compact 12 dp horizontal and 8 dp vertical page insets", kept as its own value rather than a
 * general step of this scale because it has one job [medium] and [large] do not: filling the
 * readable viewport as much as possible while still keeping text off the screen's physical edge.
 * It differs from [readerMargin] in what it insets — [readerMargin] is the margin *around* the
 * reading page as a whole, bounding where the page itself sits and what the text column pagination
 * measures against, while this value is the inset *inside* the page, between the page's own
 * boundary and its text.
 * @property readerPageVertical The vertical counterpart to [readerPageHorizontal], per the same
 * `DESIGN.md` reader text-page contract. It happens to equal [small] numerically today, but is
 * declared separately because it is pinned to the reader page inset contract specifically, not to
 * the general spacing scale — the two are free to diverge without an unrelated change to [small]
 * silently affecting reader page insets.
 */
@Immutable
data class TeddReaderSpacing(
    val none: Dp = 0.dp,
    val xxSmall: Dp = 4.dp,
    val xSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val xLarge: Dp = 32.dp,
    val xxLarge: Dp = 40.dp,
    val xxxLarge: Dp = 48.dp,
    val screenPadding: Dp = 20.dp,
    val cardPadding: Dp = 16.dp,
    val sheetPadding: Dp = 24.dp,
    val readerMargin: Dp = 20.dp,
    val touchTarget: Dp = 48.dp,
    val rowHeight: Dp = 56.dp,
    val sectionGap: Dp = 32.dp,
    val sectionHeaderGap: Dp = 8.dp,
    val itemGap: Dp = 16.dp,
    val readerPageHorizontal: Dp = 12.dp,
    val readerPageVertical: Dp = 8.dp,
)

/** The spacing the theme installs unless a caller overrides it. */
val DefaultTeddReaderSpacing = TeddReaderSpacing()
