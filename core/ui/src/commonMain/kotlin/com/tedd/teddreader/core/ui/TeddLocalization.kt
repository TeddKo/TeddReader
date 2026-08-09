package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.key
import com.tedd.teddreader.core.common.model.AppLanguage

expect object LocalAppLocale {
    val current: String
        @Composable get

    @Composable
    infix fun provides(value: String?): ProvidedValue<*>
}

fun AppLanguage.resourceLocaleTag(): String? = when (this) {
    AppLanguage.SYSTEM -> null
    AppLanguage.ENGLISH -> "en"
    AppLanguage.KOREAN -> "ko"
}

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
