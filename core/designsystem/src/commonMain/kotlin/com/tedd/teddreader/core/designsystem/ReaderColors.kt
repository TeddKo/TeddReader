package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderDarkBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderDarkTextArgb
import com.tedd.teddreader.core.common.model.ReaderLightBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderLightTextArgb
import com.tedd.teddreader.core.common.model.ReaderSepiaBackgroundArgb
import com.tedd.teddreader.core.common.model.ReaderSepiaTextArgb
import com.tedd.teddreader.core.common.model.ReaderStyle

/**
 * 읽기 페이지와 그 위의 컨트롤을 그리는 데 사용하는 모든 색상입니다.
 *
 * 읽기 페이지는 앱 크롬이 아니므로 앱 자체의 Material 팔레트와 분리되어 있습니다. 글자색과 종이색은
 * 독자가 선택하며, 그 위에 떠 있는 컨트롤은 어떤 선택에서도 읽기 쉬워야 합니다. 한데 묶어 두면 테마를
 * 하나의 값으로 전달하고 하나의 단위로 교체할 수 있습니다.
 *
 * @property text 책 본문에 사용하는 글자색입니다.
 * @property background 본문 뒤의 종이색입니다.
 * @property controls 페이지가 비쳐 보이도록 알파를 적용한, 페이지 위의 바와 시트 표면색입니다.
 * @property controlsContent [controls] 위에서 읽기 쉽도록 선택한 컨트롤 글자색입니다.
 * @property selection 선택한 텍스트 뒤에 칠하는 색상입니다.
 * @property highlight 검색 결과나 강조한 구절 뒤에 칠하는 색상입니다.
 * @property bookmark 저장한 위치를 표시하는 강조색입니다.
 * @property divider 컨트롤 행 사이의 가는 구분선 색상입니다.
 * @property dimOverlay 시트나 다이얼로그가 열렸을 때 페이지 위에 씌우는 스크림 색상입니다.
 */
@Immutable
data class ReaderColors(
    val text: Color,
    val background: Color,
    val controls: Color,
    val controlsContent: Color,
    val selection: Color,
    val highlight: Color,
    val bookmark: Color,
    val divider: Color,
    val dimOverlay: Color,
)

/** 독자의 기본값인 주간 읽기 팔레트로, 따뜻한 종이색과 검정에 가까운 글자색을 사용합니다. */
val LightReaderColors = ReaderColors(
    text = ReaderColor(ReaderLightTextArgb).toColor(),
    background = ReaderColor(ReaderLightBackgroundArgb).toColor(),
    controls = PaperWarm.copy(alpha = 0.97f),
    controlsContent = Color(0xFF1F1F1F),
    selection = SageMuted.copy(alpha = 0.40f),
    highlight = ClayPrimary.copy(alpha = 0.40f),
    bookmark = ClayPrimary,
    divider = Color(0xFFE1D8CA),
    dimOverlay = Color(0x66000000),
)

/** 어두운 방에서 눈부심을 줄이도록 어두운 종이색과 따뜻한 미색 글자를 사용하는 야간 읽기 팔레트입니다. */
val DarkReaderColors = ReaderColors(
    text = ReaderColor(ReaderDarkTextArgb).toColor(),
    background = ReaderColor(ReaderDarkBackgroundArgb).toColor(),
    controls = Color(0xF21D1B16),
    controlsContent = Color(0xFFECE6D6),
    selection = Color(0x66C8C0FF),
    highlight = Color(0x668A6A00),
    bookmark = ClayPrimary,
    divider = Color(0xFF36332D),
    dimOverlay = Color(0x99000000),
)

/** 따뜻한 조명에서 오래 읽을 때 사용하는 오래된 종이 느낌의 세피아 팔레트입니다. */
val SepiaReaderColors = ReaderColors(
    text = ReaderColor(ReaderSepiaTextArgb).toColor(),
    background = ReaderColor(ReaderSepiaBackgroundArgb).toColor(),
    controls = Color(0xF2E8D9BC),
    controlsContent = Color(0xFF3B2F24),
    selection = SageMuted.copy(alpha = 0.40f),
    highlight = Color(0x66D79A2B),
    bookmark = ClayPrimary,
    divider = Color(0xFFD8C7A3),
    dimOverlay = Color(0x66000000),
)

/** 불을 끄고 읽을 때 사용하는 [DarkReaderColors]보다 더 어두운 야간 팔레트입니다. */
val NightReaderColors = ReaderColors(
    text = Color(0xFFF2EDE2),
    background = CharcoalNight,
    controls = Color(0xF2231F24),
    controlsContent = Color(0xFFF2EDE2),
    selection = SageMuted.copy(alpha = 0.48f),
    highlight = ClayPrimary.copy(alpha = 0.38f),
    bookmark = ClayPrimary,
    divider = Color(0xFF4C463C),
    dimOverlay = Color(0xB3000000),
)

/** 최대 대비가 필요한 독자를 위해 순수한 검정과 흰색에 채도 높은 강조색을 조합한 팔레트입니다. */
val HighContrastReaderColors = ReaderColors(
    text = Color.White,
    background = Color.Black,
    controls = Color(0xFF111111),
    controlsContent = Color.White,
    selection = Color(0xFF2B61FF),
    highlight = Color(0x66FFD400),
    bookmark = Color(0xFFFFB000),
    divider = Color(0x66FFFFFF),
    dimOverlay = Color(0xCC000000),
)

/**
 * 독자가 직접 선택한 색상으로 페이지 팔레트를 만듭니다.
 *
 * 독자는 아홉 가지가 아니라 글자색과 종이색 두 가지만 선택하므로 나머지는 이 둘에서 파생합니다. 페이지가
 * 비쳐 보이도록 컨트롤은 페이지 색상의 95%를 사용하고, 컨트롤 글자는 텍스트 색상을 사용하며, 구분선은
 * 텍스트 색상의 16%를 사용합니다. 선택 영역, 강조 표시, 북마크는 페이지 색상과 관계없이 각각의 표시로
 * 알아볼 수 있어야 하므로 [LightReaderColors]의 값을 유지합니다.
 *
 * @receiver 여기의 모든 색상을 결정하는 텍스트색과 배경색을 담은 독자 고유의 스타일입니다.
 * @return 독자가 선택한 페이지 색상 위에서도 읽기 쉬운 팔레트입니다.
 */
fun ReaderStyle.readerColors(): ReaderColors = ReaderColors(
    text = textColor.toColor(),
    background = backgroundColor.toColor(),
    controls = backgroundColor.toColor().copy(alpha = 0.95f),
    controlsContent = textColor.toColor(),
    selection = LightReaderColors.selection,
    highlight = LightReaderColors.highlight,
    bookmark = LightReaderColors.bookmark,
    divider = textColor.toColor().copy(alpha = 0.16f),
    dimOverlay = LightReaderColors.dimOverlay,
)

/**
 * 저장된 색상을 Compose 색상으로 변환하며, 모델 계층과 UI 계층의 색상 타입이 만나는 유일한 지점입니다.
 *
 * @receiver 저장된 `0xAARRGGBB` 값입니다.
 * @return 알파를 포함해 Compose에서 동일하게 보이는 색상입니다.
 */
fun ReaderColor.toColor(): Color = Color(argb.toInt())
