package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * The device's own default locale, captured the first time [LocalAppLocale.provides] runs, before
 * any in-app override is applied. Kept so a null `value` passed to that function (meaning "clear the
 * override") can restore exactly what the device had, rather than falling back to whatever
 * `Locale.getDefault()` happens to return after it has already been mutated by a previous override.
 */
private var default: Locale? = null

/**
 * Android's [LocalAppLocale]: there is no scoped, Compose-local way to change which locale
 * `stringResource`/Android resource lookups use, so this mutates process-global state — both the JVM
 * default [Locale] and the [LocalContext]'s [android.content.res.Resources] `Configuration` — every
 * time the override changes.
 */
actual object LocalAppLocale {
    /** Reads the JVM's process-wide default locale, which [provides] is what actually mutates. */
    actual val current: String
        @Composable get() = Locale.getDefault().toString()

    /**
     * Mutates the process's default [Locale] and the current [LocalContext]'s resource configuration
     * to [value] (or back to the device's original [default] locale when [value] is null), via the
     * deprecated `updateConfiguration` — the only API that reliably re-resolves already-loaded
     * resources on the API levels this app supports.
     */
    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val configuration = LocalConfiguration.current
        val resources = LocalContext.current.resources
        if (default == null) default = Locale.getDefault()
        val locale = if (value == null) {
            default ?: Locale.getDefault()
        } else {
            Locale(value)
        }
        Locale.setDefault(locale)
        configuration.setLocale(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return LocalConfiguration.provides(configuration)
    }
}
