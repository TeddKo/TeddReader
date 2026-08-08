package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

internal actual fun DrawScope.drawFoundationPagerCurlShadow(
    polygon: FoundationPagerCurlPolygon,
    axis: FoundationReferenceCurlAxis,
    radius: Float,
    shadowOffset: Offset,
    color: Color,
) {
    val canonicalOffset = axis.toCanonical(shadowOffset)
    repeat(FoundationShadowLayers) { layer ->
        val fraction = (layer + 1f) / FoundationShadowLayers
        drawPath(
            path = polygon
                .translate(canonicalOffset)
                .offset(radius * fraction)
                .toPath(axis),
            color = color.copy(alpha = color.alpha / FoundationShadowLayers),
        )
    }
}

private const val FoundationShadowLayers = 8
