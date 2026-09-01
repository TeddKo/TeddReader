package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

internal actual val foundationPagerRenderProfile = FoundationPagerRenderProfile(
    threeDCurlGrid = 12,
    curlShadowLayers = 4,
)

/**
 * iOS의 해법이다 — 이 타깃의 [DrawScope]는 Android의 `Paint.setShadowLayer`처럼 블러 그림자 primitive를
 * 제공하지 않으므로, [polygon]을 여러 번 그려서 그림자를 흉내 낸다. 매 회차를 [shadowOffset] 방향으로 조금씩
 * 더 이동시키고 조금씩 더 투명하게 그려서, 딱딱한 가장자리를 가진 여러 겹이 쌓이면 하나의 부드러운 가장자리를
 * 가진 그림자처럼 보이게 한다.
 */
internal actual fun DrawScope.drawFoundationPagerCurlShadow(
    polygon: FoundationPagerCurlPolygon,
    axis: FoundationReferenceCurlAxis,
    radius: Float,
    shadowOffset: Offset,
    color: Color,
) {
    val canonicalOffset = axis.toCanonical(shadowOffset)
    val layers = foundationPagerRenderProfile.curlShadowLayers
    repeat(layers) { layer ->
        val fraction = (layer + 1f) / layers
        drawPath(
            path = polygon
                .translate(canonicalOffset)
                .offset(radius * fraction)
                .toPath(axis),
            color = color.copy(alpha = color.alpha / layers),
        )
    }
}
