package com.tedd.teddreader.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The theme's channels down through composition.
 *
 * `staticCompositionLocalOf`, not `compositionLocalOf`: a theme changes once per app, so paying to track
 * every individual reader of it buys nothing — a static local re-composes the whole subtree when it does
 * change, which is exactly what a theme switch wants.
 *
 * Private on purpose. A screen reads the theme through the accessor functions below, so the locals stay one
 * module's implementation detail and can be re-pointed without touching call sites.
 */
private val LocalTeddReaderColors = staticCompositionLocalOf { LightTeddReaderColors }

/** The app's type scale, read through [teddReaderTypography]. */
private val LocalTeddReaderTypography = staticCompositionLocalOf { DefaultTeddReaderTypography }

/** The app's spacing scale, read through [teddReaderSpacing]. */
private val LocalTeddReaderSpacing = staticCompositionLocalOf { DefaultTeddReaderSpacing }

/** The app's corner radii, read through [teddReaderShapes]. */
private val LocalTeddReaderShapes = staticCompositionLocalOf { DefaultTeddReaderShapes }

/** The app's elevation scale, read through [teddReaderElevation]. */
private val LocalTeddReaderElevation = staticCompositionLocalOf { DefaultTeddReaderElevation }

/** The app's animation durations, read through [teddReaderMotion]. */
private val LocalTeddReaderMotion = staticCompositionLocalOf { DefaultTeddReaderMotion }

/** The app's icon sizes, read through [teddReaderIconography]. */
private val LocalTeddReaderIconography = staticCompositionLocalOf { DefaultTeddReaderIconography }

/** The app's adaptive layout breakpoints, read through [teddReaderBreakpoints]. */
private val LocalTeddReaderBreakpoints = staticCompositionLocalOf { DefaultTeddReaderBreakpoints }

/** The palette the reading page draws with, read through [readerColors] — not the app's chrome palette. */
private val LocalReaderColors = staticCompositionLocalOf { LightReaderColors }

/** The app's colour roles at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderColors(): TeddReaderColors = LocalTeddReaderColors.current

/** The app's type scale at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderTypography(): TeddReaderTypography = LocalTeddReaderTypography.current

/** The app's spacing scale at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderSpacing(): TeddReaderSpacing = LocalTeddReaderSpacing.current

/** The app's corner radii at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderShapes(): TeddReaderShapes = LocalTeddReaderShapes.current

/** The app's elevation scale at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderElevation(): TeddReaderElevation = LocalTeddReaderElevation.current

/** The app's animation durations at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderMotion(): TeddReaderMotion = LocalTeddReaderMotion.current

/** The app's icon sizes at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderIconography(): TeddReaderIconography = LocalTeddReaderIconography.current

/** The app's adaptive layout breakpoints at this point in the tree. */
@Composable
@ReadOnlyComposable
fun teddReaderBreakpoints(): TeddReaderBreakpoints = LocalTeddReaderBreakpoints.current

/** The palette the *reading page* draws with, which is not the app's chrome palette. */
@Composable
@ReadOnlyComposable
fun readerColors(): ReaderColors = LocalReaderColors.current

@Composable
/**
 * Installs the app's theme: its own scales for screens, and a Material theme built from the same colours so
 * stock components match.
 *
 * The reader's palette is a *separate* parameter rather than being derived from [darkTheme], because a
 * reading page follows the reader's own choice — sepia paper in a dark app, or a custom palette — which is
 * independent of whether app chrome is dark.
 *
 * @param darkTheme whether app chrome uses the dark palette.
 * @param readerColors the palette a reading page draws with; defaults to the one matching [darkTheme], and
 * is overridden by the reader with the palette its own style resolves to.
 * @param content the app, composed under this theme.
 */
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
        LocalTeddReaderBreakpoints provides DefaultTeddReaderBreakpoints,
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
