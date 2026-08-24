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
)

/** The spacing the theme installs unless a caller overrides it. */
val DefaultTeddReaderSpacing = TeddReaderSpacing()
