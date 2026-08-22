package com.tedd.teddreader.feature.reader.impl

/**
 * One action the reader's own action menu can offer, handled either as a direct command or by
 * opening one of `ReaderOptionSheet`'s option sheets. Most names are self-explanatory from what
 * they open; the one pair worth calling out is [ToggleSavedPlace], which saves or removes the
 * *current* page as a bookmark in place, versus [SavedPlaces], which opens the full list of this
 * document's saved places.
 */
enum class ReaderMenuAction {
    Search,
    ToggleSavedPlace,
    SavedPlaces,
    TableOfContents,
    GoToPage,
    ViewOptions,
    FontOptions,
    ThemeOptions,
    PageTurnOptions,
    AutoScrollOptions,
    BrightnessOptions,
    ControlOptions,
    DocumentInfo,
}
