package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * iOS's [ReaderSystemBarsEffect]: a complete no-op. Unlike the Android `actual`, this does not hide
 * or color any system chrome, and — unlike [visible]/[backgroundColor] — [keepScreenOn] has no iOS
 * counterpart implemented here either, so nothing currently stops the device from sleeping while
 * reading on iOS the way `View.keepScreenOn` does on Android. This exists only so common code can
 * call [ReaderSystemBarsEffect] unconditionally; it is not a statement that iOS has no equivalent
 * need, only that none of it is wired up on this platform yet.
 *
 * @param visible Unused on iOS.
 * @param backgroundColor Unused on iOS.
 * @param keepScreenOn Unused on iOS; the device can sleep while reading regardless of this value.
 */
@Composable
actual fun ReaderSystemBarsEffect(
    visible: Boolean,
    backgroundColor: Color,
    keepScreenOn: Boolean,
) = Unit
