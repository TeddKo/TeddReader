package com.tedd.teddreader.core.ui

import com.tedd.teddreader.core.common.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeddLocalizationTest {
    @Test
    fun resourceLocaleTagMapsSystemToNull() {
        assertNull(AppLanguage.SYSTEM.resourceLocaleTag())
    }

    @Test
    fun resourceLocaleTagMapsExplicitLanguages() {
        assertEquals("en", AppLanguage.ENGLISH.resourceLocaleTag())
        assertEquals("ko", AppLanguage.KOREAN.resourceLocaleTag())
    }
}
