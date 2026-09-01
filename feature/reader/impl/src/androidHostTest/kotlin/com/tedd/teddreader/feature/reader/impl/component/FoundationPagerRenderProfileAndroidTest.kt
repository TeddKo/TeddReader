package com.tedd.teddreader.feature.reader.impl.component

import kotlin.test.Test
import kotlin.test.assertEquals

class FoundationPagerRenderProfileAndroidTest {
    @Test
    fun androidKeepsFullCurlSampling() {
        assertEquals(25, foundationPagerRenderProfile.threeDCurlGrid)
        assertEquals(1, foundationPagerRenderProfile.curlShadowLayers)
    }
}
