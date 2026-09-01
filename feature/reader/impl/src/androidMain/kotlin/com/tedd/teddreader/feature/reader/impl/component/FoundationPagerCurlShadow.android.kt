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
 * Android의 해법은 `android.graphics.Paint.setShadowLayer`를 통한 실제 네이티브 블러 그림자를 [polygon] 자체의
 * path를 따라 그리는 것이다. `Canvas.drawPath`는 API 28(`Build.VERSION_CODES.P`) 이상의 하드웨어 가속 캔버스에서만
 * 실제로 그림자 레이어를 렌더링한다. 그 미만에서는 실제 캔버스에 바로 그리면 그림자 레이어가 보이지 않으므로,
 * 블러가 번질 여백을 두기 위해 사방으로 [radius]의 두 배만큼 패딩을 준 오프스크린 소프트웨어 [Bitmap]에 렌더링한
 * 뒤 그 비트맵을 실제 캔버스에 다시 그려 넣는 방식으로 대체한다.
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
