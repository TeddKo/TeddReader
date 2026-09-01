package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.graphics.toArgb
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderLightBackgroundArgb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 모델에 저장된 색상과 Compose 색상 사이의 유일한 변환을 고정합니다. 저장된 `0xAARRGGBB`는 알파를 포함한
 * 모든 바이트가 그대로 돌아와야 합니다.
 *
 * 변환이 `Int`를 거치므로 고정할 가치가 있습니다. 부호 확장 오류가 생기면 반투명 오버레이가 조용히
 * 불투명해지며, 변환기 버그가 아니라 "스크림이 너무 어둡다"는 현상으로 보입니다.
 */
class ReaderColorsTest {
    /** 불투명한 페이지 색상이 왕복 변환 후에도 바뀌지 않는지 확인합니다. */
    @Test
    fun readerColorToColorPreservesOpaqueArgb() {
        val color = ReaderColor(ReaderLightBackgroundArgb).toColor()

        assertEquals(ReaderLightBackgroundArgb, color.toArgb().toLong() and 0xFFFFFFFFL)
    }

    /** 컨트롤 표면과 스크림이 의존하는 반투명 색상의 알파가 유지되는지 확인합니다. */
    @Test
    fun readerColorToColorPreservesAlpha() {
        val argb = 0x80112233L
        val color = ReaderColor(argb).toColor()

        assertEquals(argb, color.toArgb().toLong() and 0xFFFFFFFFL)
    }
}
