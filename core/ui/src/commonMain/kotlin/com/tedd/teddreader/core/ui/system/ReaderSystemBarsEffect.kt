package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Puts the reader screen into (and cleanly back out of) its own system-chrome state while it is on
 * screen: syncing the status/navigation bar color to the page background, hiding them entirely when
 * the reader's own controls are hidden, and keeping the screen awake while reading — all state that
 * belongs to the OS window, not to Compose, and that must be restored to what it was before the
 * reader took it over once the reader leaves. Android and iOS expose entirely different APIs for
 * this (or, on iOS, none at all), hence the `expect`/`actual` split rather than a shared
 * implementation.
 *
 * @param visible Whether the system status/navigation bars should be shown; false hides them for an
 * immersive reading view.
 * @param backgroundColor The reader's current page background, used both to color the system bars
 * and to decide whether their icons should render light or dark for contrast.
 * @param keepScreenOn Whether the device's screen should be prevented from sleeping while this
 * effect is active.
 */
@Composable
expect fun ReaderSystemBarsEffect(
    visible: Boolean,
    backgroundColor: Color,
    keepScreenOn: Boolean,
)
