package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.text.font.FontWeight
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 본문 텍스트를 실제로 더 굵거나 가늘게 만드는 [ReaderStyle.readerTextStyle]의 한 줄인
 * `fontWeight = FontWeight(fontWeight)`를 고정합니다. 페이지 분할기와 페이지 표면 모두 이 함수가 반환한
 * 텍스트 스타일로 측정하고 그립니다. 이 줄이 빠지거나 고정된 굵기로 돌아가면 모든 리더 굵기 설정이 그려진
 * 글리프에 조용히 반영되지 않게 됩니다. 이 테스트는 반환된 스타일의 글꼴 굵기를 설정에서 실제로 제공하는
 * 모든 굵기와 비교하여 그런 변경이 생기는 즉시 실패합니다.
 */
class ReaderTypographyTest {
    /** 글꼴 굵기 설정에서 제공하는 네 가지 굵기가 모두 변경 없이 반환 스타일에 전달되는지 확인합니다. */
    @Test
    fun readerTextStyleCarriesEachOfferedFontWeight() {
        listOf(300, 400, 500, 600).forEach { weight ->
            val style = ReaderStyle(fontWeight = weight).readerTextStyle()

            assertEquals(FontWeight(weight), style.fontWeight)
        }
    }
}
