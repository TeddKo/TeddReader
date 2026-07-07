package com.tedd.teddreader.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalTeddReaderColors = staticCompositionLocalOf { LightTeddReaderColors }
private val LocalTeddReaderTypography = staticCompositionLocalOf { DefaultTeddReaderTypography }
private val LocalTeddReaderSpacing = staticCompositionLocalOf { DefaultTeddReaderSpacing }
private val LocalTeddReaderShapes = staticCompositionLocalOf { DefaultTeddReaderShapes }
private val LocalTeddReaderElevation = staticCompositionLocalOf { DefaultTeddReaderElevation }
private val LocalTeddReaderMotion = staticCompositionLocalOf { DefaultTeddReaderMotion }
private val LocalTeddReaderIconography = staticCompositionLocalOf { DefaultTeddReaderIconography }
private val LocalReaderColors = staticCompositionLocalOf { LightReaderColors }

@Composable
@ReadOnlyComposable
fun teddReaderColors(): TeddReaderColors = LocalTeddReaderColors.current

@Composable
@ReadOnlyComposable
fun teddReaderTypography(): TeddReaderTypography = LocalTeddReaderTypography.current

@Composable
@ReadOnlyComposable
fun teddReaderSpacing(): TeddReaderSpacing = LocalTeddReaderSpacing.current

@Composable
@ReadOnlyComposable
fun teddReaderShapes(): TeddReaderShapes = LocalTeddReaderShapes.current

@Composable
@ReadOnlyComposable
fun teddReaderElevation(): TeddReaderElevation = LocalTeddReaderElevation.current

@Composable
@ReadOnlyComposable
fun teddReaderMotion(): TeddReaderMotion = LocalTeddReaderMotion.current

@Composable
@ReadOnlyComposable
fun teddReaderIconography(): TeddReaderIconography = LocalTeddReaderIconography.current

@Composable
@ReadOnlyComposable
fun readerColors(): ReaderColors = LocalReaderColors.current

@Composable
fun TeddReaderTheme(
    darkTheme: Boolean = false,
    readerColors: ReaderColors = if (darkTheme) DarkReaderColors else LightReaderColors,
    content: @Composable () -> Unit,
) {
    val appColors = if (darkTheme) DarkTeddReaderColors else LightTeddReaderColors
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = appColors.primary,
            onPrimary = appColors.onPrimary,
            primaryContainer = appColors.primaryContainer,
            onPrimaryContainer = appColors.onPrimaryContainer,
            inversePrimary = appColors.inversePrimary,
            secondary = appColors.secondary,
            onSecondary = appColors.onSecondary,
            secondaryContainer = appColors.secondaryContainer,
            onSecondaryContainer = appColors.onSecondaryContainer,
            tertiary = appColors.tertiary,
            onTertiary = appColors.onTertiary,
            tertiaryContainer = appColors.tertiaryContainer,
            onTertiaryContainer = appColors.onTertiaryContainer,
            error = appColors.error,
            onError = appColors.onError,
            errorContainer = appColors.errorContainer,
            onErrorContainer = appColors.onErrorContainer,
            background = appColors.background,
            onBackground = appColors.onBackground,
            surface = appColors.surface,
            onSurface = appColors.onSurface,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.onSurfaceVariant,
            surfaceDim = appColors.surfaceDim,
            surfaceBright = appColors.surfaceBright,
            surfaceContainerLowest = appColors.surfaceContainerLowest,
            surfaceContainerLow = appColors.surfaceContainerLow,
            surfaceContainer = appColors.surfaceContainer,
            surfaceContainerHigh = appColors.surfaceContainerHigh,
            surfaceContainerHighest = appColors.surfaceContainerHighest,
            inverseSurface = appColors.inverseSurface,
            inverseOnSurface = appColors.inverseOnSurface,
            outline = appColors.outline,
            outlineVariant = appColors.outlineVariant,
            scrim = appColors.scrim,
        )
    } else {
        lightColorScheme(
            primary = appColors.primary,
            onPrimary = appColors.onPrimary,
            primaryContainer = appColors.primaryContainer,
            onPrimaryContainer = appColors.onPrimaryContainer,
            inversePrimary = appColors.inversePrimary,
            secondary = appColors.secondary,
            onSecondary = appColors.onSecondary,
            secondaryContainer = appColors.secondaryContainer,
            onSecondaryContainer = appColors.onSecondaryContainer,
            tertiary = appColors.tertiary,
            onTertiary = appColors.onTertiary,
            tertiaryContainer = appColors.tertiaryContainer,
            onTertiaryContainer = appColors.onTertiaryContainer,
            error = appColors.error,
            onError = appColors.onError,
            errorContainer = appColors.errorContainer,
            onErrorContainer = appColors.onErrorContainer,
            background = appColors.background,
            onBackground = appColors.onBackground,
            surface = appColors.surface,
            onSurface = appColors.onSurface,
            surfaceVariant = appColors.surfaceVariant,
            onSurfaceVariant = appColors.onSurfaceVariant,
            surfaceDim = appColors.surfaceDim,
            surfaceBright = appColors.surfaceBright,
            surfaceContainerLowest = appColors.surfaceContainerLowest,
            surfaceContainerLow = appColors.surfaceContainerLow,
            surfaceContainer = appColors.surfaceContainer,
            surfaceContainerHigh = appColors.surfaceContainerHigh,
            surfaceContainerHighest = appColors.surfaceContainerHighest,
            inverseSurface = appColors.inverseSurface,
            inverseOnSurface = appColors.inverseOnSurface,
            outline = appColors.outline,
            outlineVariant = appColors.outlineVariant,
            scrim = appColors.scrim,
        )
    }

    CompositionLocalProvider(
        LocalTeddReaderColors provides appColors,
        LocalTeddReaderTypography provides DefaultTeddReaderTypography,
        LocalTeddReaderSpacing provides DefaultTeddReaderSpacing,
        LocalTeddReaderShapes provides DefaultTeddReaderShapes,
        LocalTeddReaderElevation provides DefaultTeddReaderElevation,
        LocalTeddReaderMotion provides DefaultTeddReaderMotion,
        LocalTeddReaderIconography provides DefaultTeddReaderIconography,
        LocalReaderColors provides readerColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DefaultTeddReaderTypography.toMaterialTypography(),
            shapes = DefaultTeddReaderShapes.toMaterialShapes(),
            content = content,
        )
    }
}
