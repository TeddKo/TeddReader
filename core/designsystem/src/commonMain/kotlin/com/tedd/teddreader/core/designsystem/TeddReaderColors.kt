package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 모든 테마를 조합하는 실제 안료인 앱의 원시 팔레트입니다.
 *
 * 역할이 아니라 실제 성격인 종이, 잉크, 점토, 세이지, 숯을 따라 이름을 붙였습니다. 따라서 한 안료가 여러
 * 역할을 맡을 수 있고 색상 이름을 바꾸지 않고 역할의 연결 대상을 바꿀 수 있습니다. 역할은
 * [TeddReaderColors]에 있으며, 앱에서 리터럴 색상 값을 선언하는 곳은 여기뿐입니다.
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
 * 위 안료를 조합한 앱의 색상 역할입니다.
 *
 * 의도적으로 Material 3의 역할 이름을 따릅니다. 앱이 이 값을 `MaterialTheme`에 전달하면 기본 컴포넌트가
 * 앱 색상을 사용하고, 화면도 안료에 직접 접근하지 않고 테마를 통해 같은 역할을 읽습니다. 역할을 지정한
 * 화면은 팔레트를 다시 조합해도 계속 동작하지만 안료를 지정한 화면은 그렇지 않습니다.
 *
 * 전체 테마가 컴포지션 로컬을 통해 전달되므로 `@Immutable`입니다. 이 지정이 없으면 Compose는 같은 색상을
 * 다시 받은 소비자의 컴포지션을 생략할 수 없습니다.
 *
 * @property primary 기본 버튼, 활성 컨트롤, 진행률에 사용하는 브랜드 강조색입니다.
 * @property onPrimary [primary] 위에 그리는 콘텐츠 색상입니다.
 * @property primaryContainer 선택된 행과 칩에 사용하는 강조색의 옅은 표면입니다.
 * @property onPrimaryContainer [primaryContainer] 위에 그리는 콘텐츠 색상입니다.
 * @property inversePrimary 스낵바 작업처럼 반전된 표면 위에서 보이는 강조색입니다.
 * @property secondary [primary]와 경쟁하면 안 되는 컨트롤에 사용하는 보조 강조색입니다.
 * @property onSecondary [secondary] 위에 그리는 콘텐츠 색상입니다.
 * @property secondaryContainer 보조 강조색의 옅은 표면입니다.
 * @property onSecondaryContainer [secondaryContainer] 위에 그리는 콘텐츠 색상입니다.
 * @property tertiary 앞의 두 종류와 다른 항목을 구분하는 세 번째 강조색입니다.
 * @property onTertiary [tertiary] 위에 그리는 콘텐츠 색상입니다.
 * @property tertiaryContainer 세 번째 강조색의 옅은 표면입니다.
 * @property onTertiaryContainer [tertiaryContainer] 위에 그리는 콘텐츠 색상입니다.
 * @property error 실패한 작업이나 잘못된 입력을 나타내는 색상입니다.
 * @property onError [error] 위에 그리는 콘텐츠 색상입니다.
 * @property errorContainer 메시지 뒤의 오류 표면입니다.
 * @property onErrorContainer [errorContainer] 위에 그리는 콘텐츠 색상입니다.
 * @property background 모든 요소 뒤의 창 배경입니다.
 * @property onBackground [background] 바로 위에 그리는 콘텐츠 색상입니다.
 * @property surface 카드, 바, 시트의 표면색입니다.
 * @property onSurface [surface] 위에 그리는 콘텐츠이자 앱의 기본 텍스트 색상입니다.
 * @property surfaceVariant 들어간 것처럼 보여야 하는 표면입니다.
 * @property onSurfaceVariant [onSurface]보다 한 단계 차분한 보조 텍스트와 아이콘 색상입니다.
 * @property surfaceDim 현재 테마에서 가장 어두운 표면 색조입니다.
 * @property surfaceBright 현재 테마에서 가장 밝은 표면 색조입니다.
 * @property surfaceContainerLowest [surfaceContainerHighest]까지 이어지는 다섯 가지 표면 고도 색조 중
 * 독자의 눈에서 가장 먼 색조입니다. 그림자 없이도 쌓인 표면을 구분할 수 있도록 가장 먼 곳부터 가장
 * 가까운 곳까지 단계가 이어집니다.
 * @property surfaceContainerLow 가장 낮은 단계보다 한 단계 위의 색조입니다.
 * @property surfaceContainer 컨테이너의 기본 표면 색조입니다.
 * @property surfaceContainerHigh 떠 있는 컨테이너의 색조입니다.
 * @property surfaceContainerHighest 가장 위에 있는 컨테이너의 색조입니다.
 * @property inverseSurface 스낵바처럼 테마와 반전되는 표면입니다.
 * @property inverseOnSurface [inverseSurface] 위에 그리는 콘텐츠 색상입니다.
 * @property outline 눈에 보이는 테두리 색상입니다.
 * @property outlineVariant 구분선이나 더 차분한 테두리 색상입니다.
 * @property scrim 모달 표면 뒤를 어둡게 하는 색상입니다.
 * @property ripple 앱의 모든 리플 표시에 사용하는 기준 색상입니다. Material의 기본 리플은
 * `LocalContentColor`에서 파생되어 컴포넌트마다 눌림 피드백이 달라집니다. 어떤 표면을 누르든 앱에서 하나의
 * 일관된 촉각 신호를 제공하도록 이 역할은 리플을 의도한 단일 색상으로 고정합니다.
 * @property outlineSubtle 카드와 컨테이너에 사용하는 차분한 테두리입니다. 현재 [outlineVariant]와 같은
 * 값을 사용하는 것은 의도적입니다. [outlineVariant]는 구분선과 분리선을 표시하고 [outlineSubtle]은 카드와
 * 컨테이너의 틀을 두르므로 두 역할의 목적이 다릅니다. 따라서 팔레트가 발전할 때 어느 호출 지점의 이름도
 * 바꾸지 않고 두 값을 독립적으로 달리할 수 있습니다.
 * @property shadow `Modifier.shadow`에 전달하는 직접광 및 주변광 색상입니다. 알파가 이 값에 이미 포함되어
 * 있으므로 호출자가 두 번째로 곱하면 안 됩니다. 다시 곱하면 설계한 깊이가 아니라 혼합 모드에 따라 그림자가
 * 지나치게 투명해지거나 완전히 불투명해집니다.
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

/** 따뜻한 종이 표면에 점토색과 세이지색 강조를 사용하는 앱의 주간 팔레트입니다. */
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

/** 숯색 표면에 같은 강조색의 다크 테마 색조를 적용한 앱의 야간 팔레트입니다. */
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
