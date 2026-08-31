package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

internal actual val foundationPagerRenderProfile = FoundationPagerRenderProfile(
    threeDCurlGrid = 12,
    curlShadowLayers = 4,
)

/**
 * iOS's answer, since this target's [DrawScope] exposes no blurred-shadow primitive the way
 * Android's `Paint.setShadowLayer` does: the shadow is faked by drawing [polygon] several times,
 * each pass offset a little further along [shadowOffset] and a little more transparent, so the stack
 * of hard-edged passes reads as one soft-edged shadow.
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
