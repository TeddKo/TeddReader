package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable

/**
 * iOS exposes no display-fold API: iPhone and iPad report a single flat display. A foldable iOS
 * device only needs this actual to start reporting its hinge; callers already handle a real fold.
 */
@Composable
actual fun rememberDisplayFold(): DisplayFold? = null
