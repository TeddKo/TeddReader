package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

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
)

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
)

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
)
