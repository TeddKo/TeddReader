package com.tedd.teddreader.core.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS 로케일 재정의가 같은 컴포지션 중 제공되고 `NSUserDefaults`에 기록되는지 검증한다. */
class TeddLocalizationIosTest {
    /**
     * Compose Resources가 컴포지션 중 `preferredLanguages`를 읽으므로, 새 로케일은 같은 컴포지션에서
     * 이미 `NSUserDefaults`에 저장돼 있어야 하고 하위 콘텐츠가 그 값을 읽어야 한다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun localeIsProvidedAndPersistedDuringComposition() {
        val defaults = NSUserDefaults.standardUserDefaults
        val key = "AppleLanguages"
        val original = defaults.objectForKey(key)
        try {
            defaults.setObject(listOf("en"), forKey = key)
            var storedDuringComposition: List<*>? = null
            var providedLocale: String? = null
            var languageDuringComposition: String? = null

            runComposeUiTest {
                setContent {
                    val locale = LocalAppLocale provides "ko"
                    storedDuringComposition = defaults.stringArrayForKey(key)
                    CompositionLocalProvider(locale) {
                        providedLocale = LocalAppLocale.current
                        languageDuringComposition = NSLocale.currentLocale.languageCode
                    }
                }

                waitForIdle()
                assertEquals(listOf("ko"), storedDuringComposition)
                assertEquals("ko", languageDuringComposition)
                assertEquals("ko", providedLocale)
                assertEquals(listOf("ko"), defaults.stringArrayForKey(key))
            }
        } finally {
            if (original == null) {
                defaults.removeObjectForKey(key)
            } else {
                defaults.setObject(original, forKey = key)
            }
        }
    }
}
