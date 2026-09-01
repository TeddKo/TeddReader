package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 화면이 간격에 원시 `dp` 값을 직접 쓰지 않도록 앱을 구성하는 간격 집합입니다.
 *
 * 일반 용도는 크기로 이름을 붙이고, 패딩이 의도적으로 결정된 세 표면은 위치로 이름을 붙였습니다. 따라서
 * "카드 내부"의 의미를 바꿀 때 `16.dp`를 검색하지 않고 한 곳만 수정하면 됩니다.
 *
 * 이 값들은 컴포지션 중에 읽히므로 `@Immutable`입니다. 이 지정이 없으면 Compose는 테마 전체를 불안정한
 * 값으로 취급하여 같은 테마 객체가 다시 전달될 때마다 모든 소비자를 재컴포지션합니다.
 *
 * @property none 조건문에서 간격 값을 제공해야 하지만 실제 간격은 없어야 할 때 사용하는 값입니다.
 * @property xxSmall 글리프 수준의 세부 요소 사이에 쓰는 가장 촘촘한 간격입니다.
 * @property xSmall 현재는 [xxSmall]과 같은 크기지만, 호출 지점을 건드리지 않고 둘을 달리할 수 있도록 별도
 * 단계로 유지한 값입니다.
 * @property small 서로 관련된 컨트롤 사이의 간격입니다.
 * @property medium 화면 요소 사이의 기본 간격입니다.
 * @property large 그룹 사이의 간격입니다.
 * @property xLarge 섹션 사이의 간격입니다.
 * @property xxLarge 독립된 섹션 주변의 간격입니다.
 * @property xxxLarge 빈 상태와 히어로 영역에 사용하는 가장 큰 단계입니다.
 * @property screenPadding 화면 가장자리에서 띄우는 여백입니다.
 * @property cardPadding 라이브러리 카드 내부의 여백입니다.
 * @property sheetPadding 더 가까이서 읽는 표면이므로 더 크게 둔 하단 시트 내부의 여백입니다.
 * @property readerMargin 읽기 페이지 주변의 여백이며, 페이지네이션이 측정하는 텍스트 열의 경계도
 * 결정합니다.
 * @property touchTarget 모든 상호작용 요소의 최소 한 변 길이입니다. 접근성 하한을 구현하는 값이므로 화면이
 * 좁아도 줄어들면 안 됩니다. 더 작은 터치 영역은 컨트롤을 단순히 조밀하게 만드는 것이 아니라 접근할 수
 * 없게 만듭니다.
 * @property rowHeight 목록 행의 최소 높이입니다. 텍스트 두 줄을 담는 행을 수용하도록 [touchTarget]보다
 * 큽니다. 추가 높이는 위에 임의로 더한 패딩이 아니라 텍스트 측정에서 나옵니다.
 * @property sectionGap 인접한 섹션 사이의 간격입니다. 이전에는 세 가지 구조적 간격에 모두 하나의 `large`
 * (24 dp)를 사용해 섹션 경계와 항목 경계를 시각적으로 구분할 수 없었고 화면 계층이 평평한 목록처럼
 * 보였습니다. [sectionGap], [sectionHeaderGap], [itemGap]으로 나누면 시선이 구조를 따라갈 수 있습니다.
 * @property sectionHeaderGap 섹션 제목과 바로 아래 콘텐츠 사이의 간격입니다. 제목이 두 섹션 사이에
 * 모호하게 떠 있는 대신 해당 섹션에 속한 것으로 읽히도록 [sectionGap]보다 작게 유지합니다.
 * @property itemGap 한 섹션 본문 안에서 인접한 항목 사이의 간격입니다. 세 구조적 간격 중 가장 촘촘하여,
 * 항목은 한 섹션 안의 형제이고 섹션은 시각적으로 더 강한 구분이라는 점을 드러냅니다.
 * @property readerPageHorizontal 읽기 페이지 자체의 가장자리에서 렌더링된 텍스트가 시작되는 지점까지의
 * 가로 여백입니다. `DESIGN.md`가 "컴팩트 환경에서 가로 12 dp, 세로 8 dp인 페이지 여백"으로 규정한 리더
 * 텍스트 페이지 계약입니다. 이 값에는 [medium]과 [large]에 없는 한 가지 역할, 즉 텍스트가 화면의 물리적
 * 가장자리에 닿지 않게 하면서 읽을 수 있는 뷰포트를 최대한 채우는 역할이 있으므로 일반 척도 단계와 분리해
 * 둡니다. [readerMargin]과는 여백을 적용하는 대상이 다릅니다. [readerMargin]은 읽기 페이지 전체 *주변*의
 * 여백으로 페이지 자체의 위치와 페이지네이션이 측정할 텍스트 열의 경계를 정하지만, 이 값은 페이지 자체의
 * 경계와 텍스트 사이인 페이지 *내부*의 여백입니다.
 * @property readerPageVertical 같은 `DESIGN.md` 리더 텍스트 페이지 계약에 따른 [readerPageHorizontal]의
 * 세로 대응값입니다. 현재 수치상 [small]과 같지만 일반 간격 척도가 아니라 리더 페이지 여백 계약에
 * 구체적으로 고정된 값이므로 별도로 선언합니다. 둘은 독립적으로 달라질 수 있어 [small]의 무관한 변경이
 * 리더 페이지 여백에 조용히 영향을 주지 않습니다.
 */
@Immutable
data class TeddReaderSpacing(
    val none: Dp = 0.dp,
    val xxSmall: Dp = 4.dp,
    val xSmall: Dp = 4.dp,
    val small: Dp = 8.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 24.dp,
    val xLarge: Dp = 32.dp,
    val xxLarge: Dp = 40.dp,
    val xxxLarge: Dp = 48.dp,
    val screenPadding: Dp = 20.dp,
    val cardPadding: Dp = 16.dp,
    val sheetPadding: Dp = 24.dp,
    val readerMargin: Dp = 20.dp,
    val touchTarget: Dp = 48.dp,
    val rowHeight: Dp = 56.dp,
    val sectionGap: Dp = 32.dp,
    val sectionHeaderGap: Dp = 8.dp,
    val itemGap: Dp = 16.dp,
    val readerPageHorizontal: Dp = 12.dp,
    val readerPageVertical: Dp = 8.dp,
)

/** 호출자가 재정의하지 않을 때 테마가 설치하는 간격 척도입니다. */
val DefaultTeddReaderSpacing = TeddReaderSpacing()
