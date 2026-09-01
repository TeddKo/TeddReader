package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 앱의 적응형 레이아웃을 결정하는 너비 임계값입니다.
 *
 * 각 값은 기기 모델이나 방향이 아니라 콘텐츠 너비 제약입니다. 안전 영역 여백을 제외한 뒤 너비를
 * 측정하므로 기기가 세로든 가로든 같은 임계값을 적용하며, 방향만으로 양면 레이아웃을 강제해서는 안
 * 됩니다. 너비가 넓은 기기는 세로 방향이어도 안전 영역을 제외한 너비가 [expanded]를 충족하면 확장
 * 레이아웃을 사용할 수 있고, 좁은 기기는 단지 가로로 회전했다는 이유만으로 확장 레이아웃이 되지 않습니다.
 *
 * 중단점 값은 컴포지션 중 읽히며 소비자를 무효화하지 않고 바뀌어서는 안 되므로 `@Immutable`입니다.
 * Compose는 이 객체를 안정적으로 취급하여 같은 인스턴스를 받은 소비자의 재컴포지션을 생략할 수 있습니다.
 *
 * @property compact 이 값 미만의 콘텐츠 너비는 컴팩트 윈도우 클래스(240–359 dp)입니다. 컴팩트
 * 레이아웃은 단일 열을 사용하고 작업을 세로로 쌓습니다.
 * @property medium [compact]부터 [expanded] 미만까지의 콘텐츠 너비가 중간 윈도우 클래스를 이룹니다.
 * 이 너비에서 리더가 양면 펼침으로 전환할 수 있지만 각 페이지 영역이 [minPaneWidth]를 충족할 때만 가능하며,
 * 너비만으로는 충분하지 않습니다.
 * @property expanded 이 값 이상의 콘텐츠 너비는 확장 윈도우 클래스입니다. 적응형 그리드와 상시 표시
 * 내비게이션 레일이 적합합니다.
 * @property readableMaxWidth 검색 결과, 상세 화면, 폼 같은 단일 열 읽기 표면의 최대 콘텐츠 너비입니다.
 * 열을 이 너비로 제한하면 줄 길이가 대략 45~75자 사이로 유지되며, 이 범위에서 읽기 속도와 이해도가 가장
 * 높습니다.
 * @property collectionMaxWidth 그리드 기반 라이브러리 보기 같은 컬렉션 표면의 최대 콘텐츠 너비입니다.
 * 산문 열은 줄이 길어져도 이점이 없지만 그리드는 열이 많을수록 유리하므로 [readableMaxWidth]보다 큽니다.
 * @property minPaneWidth 리더를 둘로 나눌 때 각 페이지 영역이 충족해야 하는 최소 너비입니다. 사용 가능한 콘텐츠
 * 너비가 두 페이지 영역 모두에 [minPaneWidth]를 동시에 제공하지 못하면 전체 너비와 관계없이 단일 페이지 영역
 * 레이아웃을 유지해야 합니다.
 * @property compactControlWidth 단일 컨트롤의 내부 레이아웃이 쌓인 단일 열 배치로 바뀌는 너비
 * 임계값입니다. 문서 내 검색 폼은 필드 위에 버튼을 쌓고, 저장된 위치의 빈 상태는 가운데 정렬에서 시작
 * 정렬 텍스트로 바뀝니다. [compact]와는 다릅니다. [compact]는 화면 수준 윈도우 클래스를 제어하지만, 이
 * 값은 화면 전체 윈도우 클래스와 무관하게 컨트롤이 직접 정한 크기에서 그 컨트롤 자신의 컴포지션만
 * 제어합니다. 두 임계값은 서로 바꿔 쓸 수 없으므로 [compact]를 재사용하지 않고 별도 값으로 유지합니다.
 * 여기서 [compact]를 사용하면 컨트롤이 다시 쌓이는 지점이 달라집니다.
 */
@Immutable
data class TeddReaderBreakpoints(
    val compact: Dp = 360.dp,
    val medium: Dp = 600.dp,
    val expanded: Dp = 840.dp,
    val readableMaxWidth: Dp = 720.dp,
    val collectionMaxWidth: Dp = 960.dp,
    val minPaneWidth: Dp = 280.dp,
    val compactControlWidth: Dp = 320.dp,
)

/** 호출자가 재정의하지 않을 때 테마가 설치하는 중단점입니다. */
val DefaultTeddReaderBreakpoints = TeddReaderBreakpoints()
