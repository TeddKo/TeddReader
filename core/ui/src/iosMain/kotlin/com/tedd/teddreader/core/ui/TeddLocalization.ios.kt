package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages

/** The key iOS's `AppleLanguages` user-defaults preference is stored under. */
private const val LANG_KEY = "AppleLanguages"

/** The device's own preferred language, captured the first time [LocalAppLocale.provides] runs. */
private var default: String? = null

/**
 * Backing `CompositionLocal` for [LocalAppLocale.current]. Unlike Android, iOS has no single mutable
 * "current locale" a Compose read can observe, so the override value is threaded through this
 * `CompositionLocal` instead of being read back from the system.
 */
private val LocalAppLocaleValue = staticCompositionLocalOf { "en" }

/**
 * iOS's [LocalAppLocale]: since Kotlin/Native's Foundation interop only exposes reading
 * `NSLocale.preferredLanguages`, not a scoped way to override it for Compose resource resolution
 * alone, this both writes the override into `NSUserDefaults`'s `AppleLanguages` key (mirroring what
 * changing the language in iOS Settings would do, so native code that also reads that key stays in
 * sync) and provides it through [LocalAppLocaleValue] for [current] to read back.
 */
@OptIn(InternalComposeUiApi::class)
actual object LocalAppLocale {
    /** The locale tag most recently provided through [provides], read from [LocalAppLocaleValue]. */
    actual val current: String
        @Composable get() = LocalAppLocaleValue.current

    /**
     * Persists [value] into `NSUserDefaults` under [LANG_KEY] (or removes the key when [value] is
     * null, reverting to the device's own [default] preferred language) and provides it through
     * [LocalAppLocaleValue].
     */
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
