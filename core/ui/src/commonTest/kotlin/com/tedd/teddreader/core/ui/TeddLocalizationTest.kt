package com.tedd.teddreader.core.ui

import com.tedd.teddreader.core.common.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class TeddLocalizationTest {
    @Test
    fun resolveTeddLanguageUsesSystemOnlyForSystemOption() {
        assertEquals(AppLanguage.KOREAN, resolveTeddLanguage(AppLanguage.SYSTEM, "ko"))
        assertEquals(AppLanguage.KOREAN, resolveTeddLanguage(AppLanguage.SYSTEM, "KO"))
        assertEquals(AppLanguage.KOREAN, resolveTeddLanguage(AppLanguage.SYSTEM, "ko-KR"))
        assertEquals(AppLanguage.KOREAN, resolveTeddLanguage(AppLanguage.SYSTEM, "ko_KR"))
        assertEquals(AppLanguage.ENGLISH, resolveTeddLanguage(AppLanguage.SYSTEM, "en"))
        assertEquals(AppLanguage.ENGLISH, resolveTeddLanguage(AppLanguage.SYSTEM, "ja"))
        assertEquals(AppLanguage.ENGLISH, resolveTeddLanguage(AppLanguage.SYSTEM, "en-KO"))
        assertEquals(AppLanguage.KOREAN, resolveTeddLanguage(AppLanguage.KOREAN, "en"))
        assertEquals(AppLanguage.ENGLISH, resolveTeddLanguage(AppLanguage.ENGLISH, "ko"))
    }
}
