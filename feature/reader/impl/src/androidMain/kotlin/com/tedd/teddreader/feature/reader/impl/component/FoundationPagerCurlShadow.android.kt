package com.tedd.teddreader.feature.reader.impl.component

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
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
 * Android curl shadow가 프레임 사이에 재사용하는 네이티브 그리기 자원이다. API 24~27의 오프스크린
 * Bitmap은 필요한 크기가 달라질 때만 이전 자원을 recycle하고 다시 만들며, Paint와 Path는 모든
 * 크기에서 값만 갱신한다. Compose draw는 UI 스레드에서 순차 실행되므로 한 프레임의 leaf들이 이
 * 버퍼를 차례로 비우고 사용할 수 있다.
 */
private object FoundationPagerCurlShadowBuffer {
    /** shadow layer 설정만 갱신하는 재사용 Paint. */
    val paint = Paint()

    /** 현재 polygon 윤곽을 덮어쓰는 재사용 Path. */
    val path = Path()

    /** API 24~27 소프트웨어 렌더링에 쓰는 현재 크기의 Bitmap. */
    private var bitmap: Bitmap? = null

    /** [bitmap]에 연결되어 함께 교체되는 소프트웨어 Canvas. */
    private var canvas: Canvas? = null

    /**
     * [width]와 [height]에 맞는 투명 Bitmap을 반환하고 해당 Canvas를 재사용할 준비를 한다.
     *
     * @param width 필요한 오프스크린 너비.
     * @param height 필요한 오프스크린 높이.
     * @return 이전 프레임과 크기가 같으면 같은 Bitmap, 다르면 이전 Bitmap을 recycle한 새 Bitmap.
     */
    fun prepareBitmap(width: Int, height: Int): Bitmap {
        val current = bitmap
        if (current == null || current.width != width || current.height != height) {
            current?.recycle()
            val replacement = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap = replacement
            canvas = Canvas(replacement)
        }
        val prepared = checkNotNull(bitmap)
        prepared.eraseColor(android.graphics.Color.TRANSPARENT)
        return prepared
    }

    /**
     * 보관 중인 Bitmap을 recycle하고 Canvas와 함께 비운다. 다음 [prepareBitmap] 호출이 필요한 크기로
     * 다시 만들므로, 사용하지 않는 동안 화면 크기의 ARGB_8888 Bitmap이 프로세스에 남지 않게 한다.
     */
    fun release() {
        bitmap?.recycle()
        bitmap = null
        canvas = null
    }

    /**
     * [prepareBitmap]이 준비한 Bitmap의 Canvas를 반환한다.
     *
     * @return 현재 오프스크린 Bitmap에 연결된 Canvas.
     * @throws IllegalStateException Bitmap 준비 전에 호출한 경우.
     */
    fun preparedCanvas(): Canvas = checkNotNull(canvas)

    /**
     * [polygon]을 확장하고 화면 축·패딩을 반영해 재사용 Path에 기록한다.
     *
     * @param polygon canonical 좌표계의 접힌 영역.
     * @param axis canonical 좌표를 화면 좌표로 옮길 축.
     * @param radius polygon을 바깥쪽으로 확장할 그림자 반경.
     * @param translation 오프스크린 Bitmap 안쪽으로 옮길 패딩.
     * @return 현재 프레임의 Android Path.
     */
    fun preparePath(
        polygon: FoundationPagerCurlPolygon,
        axis: FoundationReferenceCurlAxis,
        radius: Float,
        translation: Offset = Offset.Zero,
    ): android.graphics.Path {
        path.reset()
        polygon.offset(radius).vertices.forEachIndexed { index, point ->
            val actual = axis.fromCanonical(point + translation)
            if (index == 0) path.moveTo(actual.x, actual.y) else path.lineTo(actual.x, actual.y)
        }
        return path.asAndroidPath()
    }
}

/**
 * curl pager가 컴포지션을 떠날 때 [FoundationPagerCurlShadowBuffer]의 오프스크린 Bitmap을
 * 해제한다. Bitmap은 API 24~27에서만 만들어지므로 그 외 API에서는 해제할 것이 없어 무해하다.
 */
@Composable
internal actual fun FoundationPagerCurlShadowResourcesEffect() {
    DisposableEffect(Unit) {
        onDispose { FoundationPagerCurlShadowBuffer.release() }
    }
}

/**
 * Android 네이티브 shadow layer로 curl polygon의 블러 그림자를 그린다. API 28 이상은 하드웨어
 * Canvas에 직접 그리고, API 24~27은 [FoundationPagerCurlShadowBuffer]의 재사용 소프트웨어 Bitmap을
 * 거쳐 하드웨어 Canvas로 복사한다.
 *
 * @receiver 그림자가 놓일 Compose draw scope.
 * @param polygon canonical 좌표계의 접힌 영역.
 * @param axis [polygon]을 화면 좌표로 변환할 축.
 * @param radius 그림자 블러 반경.
 * @param shadowOffset polygon에서 그림자가 떨어질 화면 좌표 오프셋.
 * @param color 그림자 색상과 알파.
 */
internal actual fun DrawScope.drawFoundationPagerCurlShadow(
    polygon: FoundationPagerCurlPolygon,
    axis: FoundationReferenceCurlAxis,
    radius: Float,
    shadowOffset: Offset,
    color: Color,
) {
    val frameworkPaint = FoundationPagerCurlShadowBuffer.paint.asFrameworkPaint().apply {
        this.color = color.copy(alpha = 0f).toArgb()
        setShadowLayer(radius, shadowOffset.x, shadowOffset.y, color.toArgb())
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val path = FoundationPagerCurlShadowBuffer.preparePath(polygon, axis, radius)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawPath(path, frameworkPaint)
        }
    } else {
        val padding = 2f * radius
        val bitmapWidth = (size.width + radius * 4f).toInt().coerceAtLeast(1)
        val bitmapHeight = (size.height + radius * 4f).toInt().coerceAtLeast(1)
        val bitmap = FoundationPagerCurlShadowBuffer.prepareBitmap(bitmapWidth, bitmapHeight)
        val path = FoundationPagerCurlShadowBuffer.preparePath(
            polygon = polygon,
            axis = axis,
            radius = radius,
            translation = Offset(padding, padding),
        )
        FoundationPagerCurlShadowBuffer.preparedCanvas().drawPath(path, frameworkPaint)
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(bitmap, -padding, -padding, null)
        }
    }
}
