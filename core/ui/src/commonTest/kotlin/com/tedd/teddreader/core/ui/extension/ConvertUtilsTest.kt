package com.tedd.teddreader.core.ui.extension

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * (이 모듈의 `ConvertUtils.kt`에 정의되어 있으며 이 문서화 작업이 소유하지 않는) `Density.pxToSp`/
 * `Density.dpToSp`가 픽셀 density와 글꼴 배율이 함께 주어졌을 때 올바르게 변환하는지 검증한다. 이
 * 두 단위는 뒤바뀌기 쉽다: sp는 이미 density를 반영해 없앴지만 사용자의 글꼴 배율은 반영하지
 * 않으므로, 연산 순서를 틀리는 변환 헬퍼는 `fontScale = 1`에서는 올바르게 보이다가 사용자가 시스템
 * 글자 크기를 바꾸는 순간에야 잘못 동작하게 된다.
 */
class ConvertUtilsTest {
    /**
     * `density = 2`일 때 30px는 15dp이고, `fontScale = 1.5`일 때 15dp의 텍스트는 10sp가 된다 —
     * density 나눗셈이 글꼴 배율 나눗셈 이후가 아니라 그 전에 일어남을 확인한다.
     */
    @Test
    fun densityConvertsPixelsToSpWithDensityAndFontScale() {
        val density = Density(density = 2f, fontScale = 1.5f)

        assertEquals(10f, density.pxToSp(30f).value)
    }

    /**
     * dp 값은 이미 density와 무관하므로, sp로 변환할 때는 `fontScale`로만 나누고 `density`는 완전히
     * 무시해야 한다 — (잘못) 적용된다면 결과가 달라질 `density` 값을 골라 이를 여기서 확인한다.
     */
    @Test
    fun densityConvertsDpToSpWithFontScaleOnly() {
        val density = Density(density = 3f, fontScale = 1.5f)

        assertEquals(8f, density.dpToSp(12.dp).value)
    }
}
