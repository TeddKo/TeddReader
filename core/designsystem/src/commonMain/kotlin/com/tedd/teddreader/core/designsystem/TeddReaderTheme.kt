package com.tedd.teddreader.core.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 앱 테마 전환을 개별 소비자가 관찰하도록 현재 앱 색상 역할을 전달한다.
 *
 * 색상은 런타임에 바뀌므로 소비자만 무효화하는 [compositionLocalOf]를 사용한다. 고정 토큰은 하위 트리
 * 전체를 추적할 필요가 없어 각각 정적 로컬로 유지한다.
 */
private val LocalTeddReaderColors = compositionLocalOf { LightTeddReaderColors }

/** [teddReaderTypography]를 통해 읽는 앱의 활자 척도입니다. */
private val LocalTeddReaderTypography = staticCompositionLocalOf { DefaultTeddReaderTypography }

/** [teddReaderSpacing]을 통해 읽는 앱의 간격 척도입니다. */
private val LocalTeddReaderSpacing = staticCompositionLocalOf { DefaultTeddReaderSpacing }

/** [teddReaderShapes]를 통해 읽는 앱의 모서리 반경 척도입니다. */
private val LocalTeddReaderShapes = staticCompositionLocalOf { DefaultTeddReaderShapes }

/** [teddReaderElevation]을 통해 읽는 앱의 고도 척도입니다. */
private val LocalTeddReaderElevation = staticCompositionLocalOf { DefaultTeddReaderElevation }

/** [teddReaderMotion]을 통해 읽는 앱의 애니메이션 시간입니다. */
private val LocalTeddReaderMotion = staticCompositionLocalOf { DefaultTeddReaderMotion }

/** [teddReaderIconography]를 통해 읽는 앱의 아이콘 크기입니다. */
private val LocalTeddReaderIconography = staticCompositionLocalOf { DefaultTeddReaderIconography }

/** [teddReaderBreakpoints]를 통해 읽는 앱의 적응형 레이아웃 중단점입니다. */
private val LocalTeddReaderBreakpoints = staticCompositionLocalOf { DefaultTeddReaderBreakpoints }

/** [teddReaderStroke]를 통해 읽는 앱의 선 굵기 척도입니다. */
private val LocalTeddReaderStroke = staticCompositionLocalOf { DefaultTeddReaderStroke }

/** 런타임에 독립적으로 바뀌는 읽기 페이지 팔레트를 개별 소비자가 관찰하도록 전달한다. */
private val LocalReaderColors = compositionLocalOf { LightReaderColors }

/** 고정된 밝은 앱 팔레트를 Material 역할로 한 번 변환해 모든 재구성에서 재사용하는 값이다. */
private val LightMaterialColorScheme = lightColorScheme(
    primary = LightTeddReaderColors.primary,
    onPrimary = LightTeddReaderColors.onPrimary,
    primaryContainer = LightTeddReaderColors.primaryContainer,
    onPrimaryContainer = LightTeddReaderColors.onPrimaryContainer,
    inversePrimary = LightTeddReaderColors.inversePrimary,
    secondary = LightTeddReaderColors.secondary,
    onSecondary = LightTeddReaderColors.onSecondary,
    secondaryContainer = LightTeddReaderColors.secondaryContainer,
    onSecondaryContainer = LightTeddReaderColors.onSecondaryContainer,
    tertiary = LightTeddReaderColors.tertiary,
    onTertiary = LightTeddReaderColors.onTertiary,
    tertiaryContainer = LightTeddReaderColors.tertiaryContainer,
    onTertiaryContainer = LightTeddReaderColors.onTertiaryContainer,
    error = LightTeddReaderColors.error,
    onError = LightTeddReaderColors.onError,
    errorContainer = LightTeddReaderColors.errorContainer,
    onErrorContainer = LightTeddReaderColors.onErrorContainer,
    background = LightTeddReaderColors.background,
    onBackground = LightTeddReaderColors.onBackground,
    surface = LightTeddReaderColors.surface,
    onSurface = LightTeddReaderColors.onSurface,
    surfaceVariant = LightTeddReaderColors.surfaceVariant,
    onSurfaceVariant = LightTeddReaderColors.onSurfaceVariant,
    surfaceDim = LightTeddReaderColors.surfaceDim,
    surfaceBright = LightTeddReaderColors.surfaceBright,
    surfaceContainerLowest = LightTeddReaderColors.surfaceContainerLowest,
    surfaceContainerLow = LightTeddReaderColors.surfaceContainerLow,
    surfaceContainer = LightTeddReaderColors.surfaceContainer,
    surfaceContainerHigh = LightTeddReaderColors.surfaceContainerHigh,
    surfaceContainerHighest = LightTeddReaderColors.surfaceContainerHighest,
    inverseSurface = LightTeddReaderColors.inverseSurface,
    inverseOnSurface = LightTeddReaderColors.inverseOnSurface,
    outline = LightTeddReaderColors.outline,
    outlineVariant = LightTeddReaderColors.outlineVariant,
    scrim = LightTeddReaderColors.scrim,
)

/** 고정된 어두운 앱 팔레트를 Material 역할로 한 번 변환해 모든 재구성에서 재사용하는 값이다. */
private val DarkMaterialColorScheme = darkColorScheme(
    primary = DarkTeddReaderColors.primary,
    onPrimary = DarkTeddReaderColors.onPrimary,
    primaryContainer = DarkTeddReaderColors.primaryContainer,
    onPrimaryContainer = DarkTeddReaderColors.onPrimaryContainer,
    inversePrimary = DarkTeddReaderColors.inversePrimary,
    secondary = DarkTeddReaderColors.secondary,
    onSecondary = DarkTeddReaderColors.onSecondary,
    secondaryContainer = DarkTeddReaderColors.secondaryContainer,
    onSecondaryContainer = DarkTeddReaderColors.onSecondaryContainer,
    tertiary = DarkTeddReaderColors.tertiary,
    onTertiary = DarkTeddReaderColors.onTertiary,
    tertiaryContainer = DarkTeddReaderColors.tertiaryContainer,
    onTertiaryContainer = DarkTeddReaderColors.onTertiaryContainer,
    error = DarkTeddReaderColors.error,
    onError = DarkTeddReaderColors.onError,
    errorContainer = DarkTeddReaderColors.errorContainer,
    onErrorContainer = DarkTeddReaderColors.onErrorContainer,
    background = DarkTeddReaderColors.background,
    onBackground = DarkTeddReaderColors.onBackground,
    surface = DarkTeddReaderColors.surface,
    onSurface = DarkTeddReaderColors.onSurface,
    surfaceVariant = DarkTeddReaderColors.surfaceVariant,
    onSurfaceVariant = DarkTeddReaderColors.onSurfaceVariant,
    surfaceDim = DarkTeddReaderColors.surfaceDim,
    surfaceBright = DarkTeddReaderColors.surfaceBright,
    surfaceContainerLowest = DarkTeddReaderColors.surfaceContainerLowest,
    surfaceContainerLow = DarkTeddReaderColors.surfaceContainerLow,
    surfaceContainer = DarkTeddReaderColors.surfaceContainer,
    surfaceContainerHigh = DarkTeddReaderColors.surfaceContainerHigh,
    surfaceContainerHighest = DarkTeddReaderColors.surfaceContainerHighest,
    inverseSurface = DarkTeddReaderColors.inverseSurface,
    inverseOnSurface = DarkTeddReaderColors.inverseOnSurface,
    outline = DarkTeddReaderColors.outline,
    outlineVariant = DarkTeddReaderColors.outlineVariant,
    scrim = DarkTeddReaderColors.scrim,
)

/** 고정된 앱 활자 척도를 Material 역할로 한 번 변환해 모든 재구성에서 재사용하는 값이다. */
private val DefaultMaterialTypography = DefaultTeddReaderTypography.toMaterialTypography()

/** 고정된 앱 도형 척도를 Material 역할로 한 번 변환해 모든 재구성에서 재사용하는 값이다. */
private val DefaultMaterialShapes = DefaultTeddReaderShapes.toMaterialShapes()

/** 트리의 현재 지점에 적용된 앱 색상 역할입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderColors(): TeddReaderColors = LocalTeddReaderColors.current

/** 트리의 현재 지점에 적용된 앱 활자 척도입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderTypography(): TeddReaderTypography = LocalTeddReaderTypography.current

/** 트리의 현재 지점에 적용된 앱 간격 척도입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderSpacing(): TeddReaderSpacing = LocalTeddReaderSpacing.current

/** 트리의 현재 지점에 적용된 앱 모서리 반경 척도입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderShapes(): TeddReaderShapes = LocalTeddReaderShapes.current

/** 트리의 현재 지점에 적용된 앱 고도 척도입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderElevation(): TeddReaderElevation = LocalTeddReaderElevation.current

/** 트리의 현재 지점에 적용된 앱 애니메이션 시간입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderMotion(): TeddReaderMotion = LocalTeddReaderMotion.current

/** 트리의 현재 지점에 적용된 앱 아이콘 크기입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderIconography(): TeddReaderIconography = LocalTeddReaderIconography.current

/** 트리의 현재 지점에 적용된 앱 적응형 레이아웃 중단점입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderBreakpoints(): TeddReaderBreakpoints = LocalTeddReaderBreakpoints.current

/** 트리의 현재 지점에 적용된 앱 선 굵기 척도입니다. */
@Composable
@ReadOnlyComposable
fun teddReaderStroke(): TeddReaderStroke = LocalTeddReaderStroke.current

/** 앱 크롬 팔레트가 아니라 *읽기 페이지*를 그리는 데 사용하는 팔레트입니다. */
@Composable
@ReadOnlyComposable
fun readerColors(): ReaderColors = LocalReaderColors.current

/**
 * 화면에서 사용하는 앱 고유 척도와 같은 색상으로 만든 Material 테마를 함께 설치하여 기본 컴포넌트도
 * 앱과 어울리게 합니다.
 *
 * 읽기 페이지는 앱 크롬의 다크 모드 여부와 독립적으로 독자 자신의 선택을 따릅니다. 어두운 앱에서 세피아
 * 종이를 쓰거나 사용자 팔레트를 쓸 수 있으므로 리더 팔레트는 [darkTheme]에서 파생하지 않고 *별도*
 * 매개변수로 받습니다.
 *
 * Material의 기본 콘텐츠 색은 검정이고 `Surface`나 `Scaffold`만 다시 발행한다. 화면 프레임 밖에 놓이는
 * 리더 라벨도 현재 팔레트의 잉크를 받도록 앱 배경의 콘텐츠 색을 여기서 제공하며, 각 컨테이너는 자신의
 * 콘텐츠 색으로 계속 재정의한다.
 *
 * @param darkTheme 앱 크롬에 다크 팔레트를 사용할지 여부입니다.
 * @param readerColors 읽기 페이지를 그리는 팔레트입니다. 기본값은 [darkTheme]에 맞는 팔레트이며, 리더가
 * 자체 스타일로 결정한 팔레트로 재정의합니다.
 * @param content 이 테마 아래에서 컴포지션할 앱 콘텐츠입니다.
 */
@Composable
fun TeddReaderTheme(
    darkTheme: Boolean = false,
    readerColors: ReaderColors = if (darkTheme) DarkReaderColors else LightReaderColors,
    content: @Composable () -> Unit,
) {
    val appColors = if (darkTheme) DarkTeddReaderColors else LightTeddReaderColors
    val colorScheme = if (darkTheme) DarkMaterialColorScheme else LightMaterialColorScheme

    CompositionLocalProvider(
        LocalTeddReaderColors provides appColors,
        LocalTeddReaderTypography provides DefaultTeddReaderTypography,
        LocalTeddReaderSpacing provides DefaultTeddReaderSpacing,
        LocalTeddReaderShapes provides DefaultTeddReaderShapes,
        LocalTeddReaderElevation provides DefaultTeddReaderElevation,
        LocalTeddReaderMotion provides DefaultTeddReaderMotion,
        LocalTeddReaderIconography provides DefaultTeddReaderIconography,
        LocalTeddReaderBreakpoints provides DefaultTeddReaderBreakpoints,
        LocalTeddReaderStroke provides DefaultTeddReaderStroke,
        LocalReaderColors provides readerColors,
        LocalContentColor provides appColors.onBackground,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DefaultMaterialTypography,
            shapes = DefaultMaterialShapes,
            content = content,
        )
    }
}
