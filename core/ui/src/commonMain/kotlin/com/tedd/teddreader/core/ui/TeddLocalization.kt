package com.tedd.teddreader.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.intl.Locale
import com.tedd.teddreader.core.common.model.AppLanguage

val LocalTeddLanguage = staticCompositionLocalOf { AppLanguage.ENGLISH }

fun resolveTeddLanguage(appLanguage: AppLanguage, systemLanguage: String): AppLanguage = when (appLanguage) {
    AppLanguage.SYSTEM -> if (systemLanguage.isKoreanLanguageTag()) AppLanguage.KOREAN else AppLanguage.ENGLISH
    AppLanguage.ENGLISH,
    AppLanguage.KOREAN,
        -> appLanguage
}

@Composable
fun ProvideTeddLocalization(
    appLanguage: AppLanguage,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalTeddLanguage provides resolveTeddLanguage(appLanguage, Locale.current.language),
        content = content,
    )
}

@Composable
fun teddString(english: String, korean: String): String =
    if (LocalTeddLanguage.current == AppLanguage.KOREAN) korean else english

private fun String.isKoreanLanguageTag(): Boolean =
    lowercase().replace('_', '-').let { normalized ->
        normalized == "ko" || normalized.startsWith("ko-")
    }
