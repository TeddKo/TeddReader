package com.tedd.teddreader.feature.reader.impl.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb


internal actual val foundationPagerRenderProfile = FoundationPagerRenderProfile(
    threeDCurlGrid = 25,
    curlShadowLayers = 1,
)
/**
 * Android's answer: a real, native blurred shadow via `android.graphics.Paint.setShadowLayer`,
 * drawn along [polygon]'s own path. `Canvas.drawPath` only actually renders a shadow layer on a
 * hardware-accelerated canvas from API 28 (`Build.VERSION_CODES.P`) onward; below that, the shadow
 * layer is invisible if drawn straight into the real canvas, so this falls back to rendering into
 * an offscreen software [Bitmap] — padded by twice [radius] on every side so the blur has room to
 * bleed — and blitting that bitmap back into the real canvas afterward.
 */
internal actual fun DrawScope.drawFoundationPagerCurlShadow(
    polygon: FoundationPagerCurlPolygon,
    axis: FoundationReferenceCurlAxis,
    radius: Float,
    shadowOffset: Offset,
    color: Color,
) {
    val paint = Paint().apply {
        asFrameworkPaint().apply {
            this.color = color.copy(alpha = 0f).toArgb()
            setShadowLayer(radius, shadowOffset.x, shadowOffset.y, color.toArgb())
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(
                polygon.offset(radius).toPath(axis).asAndroidPath(),
                paint.asFrameworkPaint(),
            )
        }
    } else {
        val bitmap = Bitmap.createBitmap(
            (size.width + radius * 4f).toInt(),
            (size.height + radius * 4f).toInt(),
            Bitmap.Config.ARGB_8888,
        )
        try {
            Canvas(bitmap).drawPath(
                polygon
                    .translate(Offset(2f * radius, 2f * radius))
                    .offset(radius)
                    .toPath(axis)
                    .asAndroidPath(),
                paint.asFrameworkPaint(),
            )
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(bitmap, -2f * radius, -2f * radius, null)
            }
        } finally {
            bitmap.recycle()
        }
    }
}
