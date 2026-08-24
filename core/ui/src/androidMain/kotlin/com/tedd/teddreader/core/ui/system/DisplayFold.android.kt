package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo

/**
 * Android's [rememberDisplayFold]: subscribes to Jetpack Window Manager's
 * [WindowInfoTracker]/[WindowLayoutInfo] for this [LocalContext], takes the first reported
 * [FoldingFeature] (a device can, in principle, report more than one; this app's two-page layout
 * only ever needs the primary fold), and converts its pixel bounds to dp via [LocalDensity] since
 * [DisplayFold] is defined in dp while [FoldingFeature.bounds] is reported in raw pixels.
 *
 * @return null when the current window reports no [FoldingFeature] at all (a non-foldable device, or
 * a foldable that is not currently split); otherwise the converted [DisplayFold].
 */
@Composable
actual fun rememberDisplayFold(): DisplayFold? {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutInfoFlow = remember(context) { WindowInfoTracker.getOrCreate(context).windowLayoutInfo(context) }
    val layoutInfo: WindowLayoutInfo? by layoutInfoFlow.collectAsStateWithLifecycle(initialValue = null)

    val fold = layoutInfo?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull() ?: return null
    val isVertical = fold.orientation == FoldingFeature.Orientation.VERTICAL
    return with(density) {
        DisplayFold(
            startDp = (if (isVertical) fold.bounds.left else fold.bounds.top).toDp().value,
            endDp = (if (isVertical) fold.bounds.right else fold.bounds.bottom).toDp().value,
            isVertical = isVertical,
            isSeparating = fold.isSeparating,
        )
    }
}
