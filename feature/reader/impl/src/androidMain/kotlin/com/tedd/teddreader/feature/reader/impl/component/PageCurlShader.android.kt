package com.tedd.teddreader.feature.reader.impl.component

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.tedd.teddreader.core.common.model.PageTurnMode

internal actual fun platformPageCurlShaderSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

@Composable
internal actual fun PlatformPageCurlShaderOverlay(
    dragOffset: Float,
    crossOffset: Float,
    progress: Float,
    pageTurnMode: PageTurnMode,
    preset: CurlPreset,
    modifier: Modifier,
) {
    if (!platformPageCurlShaderSupported() || progress <= 0f) return
    AndroidPageCurlShaderOverlay(
        dragOffset = dragOffset,
        crossOffset = crossOffset,
        progress = progress,
        pageTurnMode = pageTurnMode,
        preset = preset,
        modifier = modifier,
    )
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AndroidPageCurlShaderOverlay(
    dragOffset: Float,
    crossOffset: Float,
    progress: Float,
    pageTurnMode: PageTurnMode,
    preset: CurlPreset,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(PageCurlAgls) }
    val paint = remember { Paint() }
    Canvas(modifier = modifier) {
        shader.setFloatUniform("resolution", size.width, size.height)
        shader.setFloatUniform("progress", progress.coerceIn(0f, 1f))
        shader.setFloatUniform("direction", if (dragOffset < 0f) 1f else -1f)
        shader.setFloatUniform("crossOffset", crossOffset)
        shader.setFloatUniform("vertical", if (pageTurnMode.isVerticalCurlMode()) 1f else 0f)
        shader.setFloatUniform("shadowAlpha", preset.shadowAlpha)
        shader.setFloatUniform("highlightAlpha", preset.highlightAlpha)
        shader.setFloatUniform("shadowSize", preset.shadowSizeRatio)
        shader.setFloatUniform("backsideAlpha", preset.backsideAlpha)
        shader.setFloatUniform("creaseAlpha", preset.creaseAlpha)
        shader.setFloatUniform("diagonalRatio", preset.diagonalRatio)
        shader.setFloatUniform("appleStyle", preset.appleStyle)
        paint.shader = shader
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
    }
}

private fun PageTurnMode.isVerticalCurlMode(): Boolean =
    this == PageTurnMode.VERTICAL || this == PageTurnMode.CONTINUOUS

private const val PageCurlAgls = """
uniform float2 resolution;
uniform float progress;
uniform float direction;
uniform float crossOffset;
uniform float vertical;
uniform float shadowAlpha;
uniform float highlightAlpha;
uniform float shadowSize;
uniform float backsideAlpha;
uniform float creaseAlpha;
uniform float diagonalRatio;
uniform float appleStyle;

half4 main(float2 coord) {
    float2 uv = coord / resolution;
    float axis = mix(uv.x, uv.y, vertical);
    float cross = mix(uv.y, uv.x, vertical);
    float crossExtent = mix(resolution.y, resolution.x, vertical);
    float crossDrag = clamp(crossOffset / max(crossExtent, 1.0), -0.5, 0.5);
    float foldBase = direction > 0.0 ? (1.0 - progress) : progress;
    float lean = diagonalRatio * (0.08 + (0.22 * progress)) + (crossDrag * 0.32);
    float fold = clamp(foldBase + ((cross - 0.5) * lean * direction), 0.0, 1.0);
    float signedDistance = axis - fold;
    float distanceToCrease = abs(signedDistance);
    float foldedSide = direction > 0.0 ? step(fold, axis) : step(axis, fold);
    float creaseWidth = mix(0.024, 0.040, appleStyle);
    float shadowPulse = clamp(1.0 - (abs(progress - 0.7) / 0.7), 0.35, 1.0);
    float crease = smoothstep(creaseWidth, 0.0, distanceToCrease) * progress;
    float broad = smoothstep(shadowSize, 0.0, distanceToCrease) * progress * shadowPulse;
    float shadow = broad * shadowAlpha * (1.0 - (foldedSide * 0.50));
    float creaseDark = crease * creaseAlpha;
    float highlightDistance = abs(signedDistance - (direction * 0.014));
    float highlight = smoothstep(0.022, 0.0, highlightDistance) * highlightAlpha * progress * 0.60;
    float dark = max(shadow, creaseDark);
    float alpha = min(max(dark, highlight), 0.46);
    float tone = highlight > dark ? 1.0 : 0.0;
    return half4(tone, tone, tone, alpha);
}
"""
