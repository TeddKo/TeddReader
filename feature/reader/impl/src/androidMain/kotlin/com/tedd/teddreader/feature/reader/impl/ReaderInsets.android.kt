package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.runtime.Composable

/**
 * Android's answer: `systemBarsIgnoringVisibility`, which reports the system bars' full size
 * regardless of whether they are currently drawn. Plain `WindowInsets.systemBars` reports zero for
 * a bar Android is momentarily hiding — for instance while a gesture-nav swipe is transiently
 * revealing a bar that is otherwise hidden, or under a fullscreen request — which would make the
 * reader's own reserved padding jump every time a bar's visibility toggles rather than only when
 * its actual size changes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal actual fun readerSystemBarsInsets(): WindowInsets = WindowInsets.systemBarsIgnoringVisibility
