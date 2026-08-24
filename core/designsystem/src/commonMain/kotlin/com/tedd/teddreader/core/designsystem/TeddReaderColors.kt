package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The app's raw palette: the actual pigments every theme is mixed from.
 *
 * Named after what they are — paper, ink, clay, sage, charcoal — rather than after the roles they fill, so
 * one pigment can serve several roles and a role can be re-pointed without renaming a colour. Roles live in
 * [TeddReaderColors]; these are the only literal colour values in the app.
 */
val PaperEggshell = Color(0xFFF7F1E7)
val PaperWarm = Color(0xFFFBF7F0)
val InkNearBlack = Color(0xFF191613)
val InkMuted = Color(0xFF4D473F)
val ClayPrimary = Color(0xFF9F5E46)
val ClaySoft = Color(0xFFE8D2C7)
val SageMuted = Color(0xFF65735D)
val SageSoft = Color(0xFFDDE4D8)
val CharcoalNight = Color(0xFF1A1C1A)
val CharcoalRaised = Color(0xFF242826)
val EmberError = Color(0xFFB24C3B)

/**
 * The app's colour roles, mixed from the pigments above.
 *
 * Mirrors Material 3's role names on purpose: the app hands this to `MaterialTheme` so stock components
 * pick the app's colours up, while screens read the same roles through the theme instead of reaching for a
 * pigment directly. A screen that names a role keeps working when the palette is re-mixed; a screen that
 * names a pigment does not.
 *
 * `@Immutable` because the whole theme is passed down through composition locals — without it Compose
 * cannot skip a consumer that received the same colours again.
 *
 * @property primary the brand accent: primary buttons, active controls, progress.
 * @property onPrimary content drawn on [primary].
 * @property primaryContainer the accent's tinted surface, for selected rows and chips.
 * @property onPrimaryContainer content drawn on [primaryContainer].
 * @property inversePrimary the accent as it reads on an inverted surface, e.g. a snackbar action.
 * @property secondary the supporting accent, for controls that must not compete with [primary].
 * @property onSecondary content drawn on [secondary].
 * @property secondaryContainer the supporting accent's tinted surface.
 * @property onSecondaryContainer content drawn on [secondaryContainer].
 * @property tertiary the third accent, used to separate a distinct kind of item from the first two.
 * @property onTertiary content drawn on [tertiary].
 * @property tertiaryContainer that accent's tinted surface.
 * @property onTertiaryContainer content drawn on [tertiaryContainer].
 * @property error a failed action or invalid input.
 * @property onError content drawn on [error].
 * @property errorContainer the error surface behind a message.
 * @property onErrorContainer content drawn on [errorContainer].
 * @property background the window behind everything.
 * @property onBackground content drawn straight on [background].
 * @property surface a card, bar or sheet.
 * @property onSurface content drawn on [surface] — the app's default text colour.
 * @property surfaceVariant a surface that has to read as recessed.
 * @property onSurfaceVariant secondary text and icons, one step quieter than [onSurface].
 * @property surfaceDim the darkest surface tone of the current theme.
 * @property surfaceBright the lightest surface tone of the current theme.
 * @property surfaceContainerLowest through [surfaceContainerHighest] the five surface elevation tones, from
 * furthest below the reader's eye to closest, so stacked surfaces stay distinguishable without shadows.
 * @property surfaceContainerLow one step above lowest.
 * @property surfaceContainer the resting surface tone for a container.
 * @property surfaceContainerHigh a raised container.
 * @property surfaceContainerHighest the topmost container tone.
 * @property inverseSurface a surface that inverts against the theme, e.g. a snackbar.
 * @property inverseOnSurface content drawn on [inverseSurface].
 * @property outline a visible border.
 * @property outlineVariant a divider or a quieter border.
 * @property scrim the dim behind a modal surface.
 * @property ripple The reference colour for all ripple indications throughout the app. Material's
 * default ripple derives from `LocalContentColor`, which makes the pressed feedback vary from
 * component to component. This role pins the ripple to one deliberate colour so the app has a
 * single, consistent tactile signal regardless of which surface is pressed.
 * @property outlineSubtle A quiet border for cards and containers. Today this carries the same
 * value as [outlineVariant], which is intentional: the two roles serve different purposes —
 * [outlineVariant] marks dividers and separators, while [outlineSubtle] frames cards and containers
 * — so they can diverge independently when the palette evolves without renaming either call site.
 * @property shadow The spot-and-ambient colour passed to `Modifier.shadow`. The alpha is baked
 * into this value so callers do not multiply it a second time; multiplying again would make
 * shadows either too transparent or fully opaque depending on the blend mode, rather than the
 * designed depth.
 */
@Immutable
data class TeddReaderColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceDim: Color,
    val surfaceBright: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val outline: Color,
    val outlineVariant: Color,
    val scrim: Color,
    val ripple: Color,
    val outlineSubtle: Color,
    val shadow: Color,
)

/** The app's day palette: warm paper surfaces with clay and sage accents. */
val LightTeddReaderColors = TeddReaderColors(
    primary = ClayPrimary,
    onPrimary = PaperWarm,
    primaryContainer = ClaySoft,
    onPrimaryContainer = InkNearBlack,
    inversePrimary = Color(0xFFC98F78),
    secondary = SageMuted,
    onSecondary = PaperWarm,
    secondaryContainer = SageSoft,
    onSecondaryContainer = InkNearBlack,
    tertiary = Color(0xFF6D6257),
    onTertiary = PaperWarm,
    tertiaryContainer = Color(0xFFE7DDD3),
    onTertiaryContainer = InkNearBlack,
    error = EmberError,
    onError = PaperWarm,
    errorContainer = Color(0xFFF8D8D2),
    onErrorContainer = Color(0xFF4C1B12),
    background = PaperEggshell,
    onBackground = InkNearBlack,
    surface = PaperEggshell,
    onSurface = InkNearBlack,
    surfaceVariant = Color(0xFFE6DED2),
    onSurfaceVariant = InkMuted,
    surfaceDim = Color(0xFFEEE6DA),
    surfaceBright = PaperWarm,
    surfaceContainerLowest = PaperWarm,
    surfaceContainerLow = Color(0xFFF5EEE3),
    surfaceContainer = Color(0xFFF0E8DC),
    surfaceContainerHigh = Color(0xFFEAE2D6),
    surfaceContainerHighest = Color(0xFFE3DBCF),
    inverseSurface = Color(0xFF2C2925),
    inverseOnSurface = Color(0xFFF7F1E7),
    outline = Color(0xFF8A8177),
    outlineVariant = Color(0xFFD1C7BA),
    scrim = Color.Black,
    ripple = InkNearBlack,
    outlineSubtle = Color(0xFFD1C7BA),
    shadow = InkNearBlack.copy(alpha = 0.20f),
)

/** The app's night palette: charcoal surfaces carrying the same accents at their dark-theme tones. */
val DarkTeddReaderColors = TeddReaderColors(
    primary = Color(0xFFD5A38D),
    onPrimary = Color(0xFF3F2318),
    primaryContainer = Color(0xFF734735),
    onPrimaryContainer = Color(0xFFF6DDD2),
    inversePrimary = ClayPrimary,
    secondary = Color(0xFFB8C7AF),
    onSecondary = Color(0xFF253023),
    secondaryContainer = Color(0xFF3C4A3A),
    onSecondaryContainer = Color(0xFFE3EDD9),
    tertiary = Color(0xFFD0C2B4),
    onTertiary = Color(0xFF322A22),
    tertiaryContainer = Color(0xFF4D433A),
    onTertiaryContainer = Color(0xFFF0E3D5),
    error = Color(0xFFFFB4A7),
    onError = Color(0xFF690F06),
    errorContainer = Color(0xFF8B2B1E),
    onErrorContainer = Color(0xFFFFDAD4),
    background = CharcoalNight,
    onBackground = Color(0xFFF0E8DC),
    surface = CharcoalNight,
    onSurface = Color(0xFFF0E8DC),
    surfaceVariant = Color(0xFF48433D),
    onSurfaceVariant = Color(0xFFD0C7BC),
    surfaceDim = Color(0xFF151715),
    surfaceBright = Color(0xFF313531),
    surfaceContainerLowest = Color(0xFF121412),
    surfaceContainerLow = Color(0xFF1D211E),
    surfaceContainer = CharcoalRaised,
    surfaceContainerHigh = Color(0xFF2C312E),
    surfaceContainerHighest = Color(0xFF363B38),
    inverseSurface = Color(0xFFF0E8DC),
    inverseOnSurface = CharcoalNight,
    outline = Color(0xFF9B9389),
    outlineVariant = Color(0xFF48433D),
    scrim = Color.Black,
    ripple = Color(0xFFF0E8DC),
    outlineSubtle = Color(0xFF48433D),
    shadow = Color.Black.copy(alpha = 0.40f),
)
