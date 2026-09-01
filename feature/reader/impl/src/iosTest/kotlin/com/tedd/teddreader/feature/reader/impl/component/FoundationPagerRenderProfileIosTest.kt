package com.tedd.teddreader.feature.reader.impl.component

import kotlin.test.Test
import kotlin.test.assertEquals

class FoundationPagerRenderProfileIosTest {
    @Test
    fun iosUsesFrameBudgetedCurlSampling() {
        assertEquals(12, foundationPagerRenderProfile.threeDCurlGrid)
        assertEquals(4, foundationPagerRenderProfile.curlShadowLayers)
    }
}
