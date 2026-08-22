package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable

/**
 * The three animation lengths the app uses, so timings stay consistent between screens.
 *
 * Milliseconds rather than `Duration` because every Compose animation spec takes an `Int` of them, and
 * converting at each call site is noise.
 *
 * @property shortDurationMs a state change on a control the finger is already on.
 * @property mediumDurationMs the default transition between states of one screen.
 * @property longDurationMs a transition that moves a whole surface, such as a sheet.
 */
@Immutable
data class TeddReaderMotion(
    val shortDurationMs: Int = 120,
    val mediumDurationMs: Int = 200,
    val longDurationMs: Int = 300,
)

/** The motion scale the theme installs unless a caller overrides it. */
val DefaultTeddReaderMotion = TeddReaderMotion()
