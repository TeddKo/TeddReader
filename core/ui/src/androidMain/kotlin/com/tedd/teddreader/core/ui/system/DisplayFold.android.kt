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
 * [rememberDisplayFold]의 Android 구현체. 이 [LocalContext]에 대해 Jetpack Window Manager의
 * [WindowInfoTracker]/[WindowLayoutInfo]를 구독하고, 처음 보고된 [FoldingFeature]를 취한다(기기는
 * 원칙적으로 두 개 이상을 보고할 수 있지만, 이 앱의 2페이지 레이아웃은 항상 주 폴드 하나만 필요로
 * 한다). [DisplayFold]는 dp 단위로 정의되는 반면 [FoldingFeature.bounds]는 원시 픽셀로 보고되므로,
 * [LocalDensity]를 통해 픽셀 경계를 dp로 변환한다.
 *
 * @return 현재 창이 [FoldingFeature]를 전혀 보고하지 않으면(폴더블이 아닌 기기이거나, 폴더블이지만
 * 현재 분할되지 않은 경우) null. 그렇지 않으면 변환된 [DisplayFold].
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
