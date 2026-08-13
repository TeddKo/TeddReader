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
