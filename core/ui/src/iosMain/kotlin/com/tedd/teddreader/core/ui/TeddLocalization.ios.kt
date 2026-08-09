package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

private const val LANG_KEY = "AppleLanguages"
private var default: String? = null
private val LocalAppLocaleValue = staticCompositionLocalOf { "en" }

@OptIn(InternalComposeUiApi::class)
actual object LocalAppLocale {
    actual val current: String
        @Composable get() = LocalAppLocaleValue.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        if (default == null) {
            default = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en"
        }
        val new = value ?: default ?: "en"
        if (value == null) {
            NSUserDefaults.standardUserDefaults.removeObjectForKey(LANG_KEY)
        } else {
            NSUserDefaults.standardUserDefaults.setObject(listOf(new), forKey = LANG_KEY)
        }
        return LocalAppLocaleValue.provides(new)
    }
}
