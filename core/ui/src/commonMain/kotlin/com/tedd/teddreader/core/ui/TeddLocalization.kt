package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key
import com.tedd.teddreader.core.common.model.AppLanguage

/**
 * Platform hook for overriding the app's display language independently of the device's system
 * locale, so a user can pick English or Korean inside the app without changing their OS setting.
 * Android and iOS need entirely different mechanisms to make that override actually affect loaded
 * string resources — Android mutates the `Configuration`/`Resources` locale in place, iOS swaps
 * `AppleLanguages` and a `CompositionLocal` — so this is an `expect`/`actual` object rather than a
 * shared implementation.
 */
expect object LocalAppLocale {
    /** The locale tag currently in effect for resource lookups, e.g. `"en"`, `"ko"`, or a system tag. */
    val current: String
        @Composable get

    /**
     * Installs [value] as the active locale for everything composed under the returned provider.
     *
     * @param value A resource locale tag such as `"en"`/`"ko"`, or null to fall back to the device's
     * own locale.
     * @return A [ProvidedValue] suitable for [CompositionLocalProvider].
     */
    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

/**
 * Maps [AppLanguage] — the app's own setting — to the locale tag [LocalAppLocale.provides] and
 * Compose Multiplatform's resource system expect, keeping that mapping in one place instead of
 * letting each caller spell out the `"en"`/`"ko"` tags itself.
 *
 * @receiver The user's chosen app language.
 * @return `"en"`/`"ko"` for an explicit language choice, or null for [AppLanguage.SYSTEM] to mean
 * "use whatever locale the device is already in."
 */
fun AppLanguage.resourceLocaleTag(): String? = when (this) {
    AppLanguage.SYSTEM -> null
    AppLanguage.ENGLISH -> "en"
    AppLanguage.KOREAN -> "ko"
}

/**
 * Applies the user's [appLanguage] choice to everything under [content], and forces recomposition
 * of that subtree via `key(localeTag)` whenever the resolved tag changes — needed because Compose
 * Multiplatform's `stringResource` calls are not automatically recomposed just because
 * [LocalAppLocale] changed; without the `key` wrap, a language switch would leave already-composed
 * text showing the previous language until something else happened to recompose it.
 *
 * @param appLanguage The language to apply for [content], as chosen in app settings.
 * @param content The subtree that should see [appLanguage] applied.
 */
@Composable
fun ProvideTeddLocalization(
    appLanguage: AppLanguage,
    content: @Composable () -> Unit,
) {
    val localeTag = appLanguage.resourceLocaleTag()
    CompositionLocalProvider(LocalAppLocale provides localeTag) {
        key(localeTag) {
            content()
        }
    }
}
