package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * iOS's answer, since this target's [DrawScope] exposes no blurred-shadow primitive the way
 * Android's `Paint.setShadowLayer` does: the shadow is faked by drawing [polygon]
 * [FoundationShadowLayers] times, each pass offset a little further along [shadowOffset] and a
 * little more transparent, so the stack of hard-edged passes reads as one soft-edged shadow.
 */
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

/**
 * How many translucent passes [drawFoundationPagerCurlShadow] layers to fake a blur. Each extra
 * layer costs one more path fill and buys a smoother-looking gradient at the fold's edge; 8 keeps
 * the individual passes from reading as visible banding while still being cheap enough to redraw
 * on every frame of a drag-driven page turn.
 */
private const val FoundationShadowLayers = 8
