package com.tedd.teddreader.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 컴포지션을 통해 아래로 전달되는 테마 채널입니다.
 *
 * `staticCompositionLocalOf`를 사용하며 `compositionLocalOf`는 사용하지 않습니다. 테마는 앱에서 한 번씩 바뀌므로
 * 개별 소비자를 모두 추적해도 이점이 없습니다. 정적 로컬은 값이 바뀔 때 하위 트리 전체를 다시
 * 컴포지션하며, 이는 테마 전환에 정확히 필요한 동작입니다.
 *
 * 의도적으로 비공개입니다. 화면은 아래 접근자 함수를 통해 테마를 읽으므로 로컬은 이 모듈의 구현 세부
 * 사항으로 남고, 호출 지점을 건드리지 않고도 연결 대상을 바꿀 수 있습니다.
 */
private val LocalTeddReaderColors = staticCompositionLocalOf { LightTeddReaderColors }

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

/** 앱 크롬 팔레트가 아니라 [readerColors]를 통해 읽는 읽기 페이지용 팔레트입니다. */
private val LocalReaderColors = staticCompositionLocalOf { LightReaderColors }

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

/** 앱 크롬 팔레트가 아니라 *읽기 페이지*를 그리는 데 사용하는 팔레트입니다. */
@Composable
@ReadOnlyComposable
fun readerColors(): ReaderColors = LocalReaderColors.current

@Composable
/**
 * 화면에서 사용하는 앱 고유 척도와 같은 색상으로 만든 Material 테마를 함께 설치하여 기본 컴포넌트도
 * 앱과 어울리게 합니다.
 *
 * 읽기 페이지는 앱 크롬의 다크 모드 여부와 독립적으로 독자 자신의 선택을 따릅니다. 어두운 앱에서 세피아
 * 종이를 쓰거나 사용자 팔레트를 쓸 수 있으므로 리더 팔레트는 [darkTheme]에서 파생하지 않고 *별도*
 * 매개변수로 받습니다.
 *
 * @param darkTheme 앱 크롬에 다크 팔레트를 사용할지 여부입니다.
 * @param readerColors 읽기 페이지를 그리는 팔레트입니다. 기본값은 [darkTheme]에 맞는 팔레트이며, 리더가
 * 자체 스타일로 결정한 팔레트로 재정의합니다.
 * @param content 이 테마 아래에서 컴포지션할 앱 콘텐츠입니다.
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
