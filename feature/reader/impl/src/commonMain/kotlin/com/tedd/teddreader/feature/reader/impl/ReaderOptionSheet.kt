package com.tedd.teddreader.feature.reader.impl

/**
 * Identifies which reader settings surface is currently open, or is `null` on
 * [ReaderUiState.activeSheet][com.tedd.teddreader.feature.reader.impl.ReaderUiState] when none is.
 * Kept as a sealed hierarchy of empty markers, rather than a shared enum plus one boolean flag per
 * surface, so [ReaderUiState][com.tedd.teddreader.feature.reader.impl.ReaderUiState] has exactly one
 * field to hold "what's open" and `ReaderScreen`'s dispatch over it can be an exhaustive `when` —
 * adding a new settings surface means adding one member here and one branch there, never a second
 * flag that can silently drift out of sync with the first.
 *
 * Every member here opens through the same modal bottom sheet in `ReaderScreen`, with one exception:
 * [TableOfContents] is filtered out before that dispatch and instead drives a side navigation
 * drawer, because an outline the reader is jumping around in behaves like navigation chrome, not a
 * settings form, and needs the drawer's own open/close affordances rather than a sheet's.
 */
sealed interface ReaderOptionSheet {
    /**
     * The document outline. Unlike every other member of this hierarchy it does not render through
     * the shared modal bottom sheet — `ReaderScreen` special-cases it into a side navigation drawer,
     * since jumping to a heading is a navigation action rather than a setting to adjust.
     */
    data object TableOfContents : ReaderOptionSheet

    /** A page-number entry field and a jump button, bounded by the document's known page count. */
    data object GoToPage : ReaderOptionSheet

    /** Whole-screen viewing behavior: keep-screen-on, fullscreen, the progress bar toggle, and, for a visual/PDF document, the zoom level. */
    data object View : ReaderOptionSheet

    /** Text rendering for text-based formats: font size, line height, and font family, with a live preview of the combined result. */
    data object Font : ReaderOptionSheet

    /** The reader's color scheme (system/light/dark/sepia), previewed against the current page style before committing. */
    data object Theme : ReaderOptionSheet

    /** How a page turn is triggered and animated: turn axis, the default transition, and the page-turn effect. */
    data object PageTurn : ReaderOptionSheet

    /** Auto-scroll enablement, its mode (pixel/line/page), and its speed — the mode picker itself disables the line mode while a visual document is open, since there is no text to scroll by line. */
    data object AutoScroll : ReaderOptionSheet

    /** The dimming overlay drawn over the whole screen, for reading below the display's own minimum brightness floor. */
    data object Brightness : ReaderOptionSheet

    /**
     * A single, narrowly-scoped switch for the bottom bar's page-progress display — the same toggle
     * [View] also exposes alongside its broader screen-behavior options, offered here again on its
     * own with an explanatory description for a reader who only wants to reach that one setting.
     */
    data object Controls : ReaderOptionSheet
}
