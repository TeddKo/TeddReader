package com.tedd.teddreader.core.ui

import com.tedd.teddreader.core.common.model.AppLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies [AppLanguage.resourceLocaleTag]'s mapping, since [LocalAppLocale.provides] and
 * [ProvideTeddLocalization] both depend on it choosing the right tag (or null) for every
 * [AppLanguage] value — a wrong mapping here would silently force the wrong resource locale, or fail
 * to fall back to the system locale, for every screen in the app.
 */
class TeddLocalizationTest {
    /** [AppLanguage.SYSTEM] must map to null, meaning "use the device's own locale, not an override." */
    @Test
    fun resourceLocaleTagMapsSystemToNull() {
        assertNull(AppLanguage.SYSTEM.resourceLocaleTag())
    }

    /** Each explicit [AppLanguage] value maps to its own resource locale tag. */
    @Test
    fun resourceLocaleTagMapsExplicitLanguages() {
        assertEquals("en", AppLanguage.ENGLISH.resourceLocaleTag())
        assertEquals("ko", AppLanguage.KOREAN.resourceLocaleTag())
    }
}
