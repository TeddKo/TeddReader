package com.tedd.teddreader.core.ui.extension

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderStroke
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * 두 번째로 받아들여진 탭이 의도하지 않은 반복으로 취급되는 시간 창.
 *
 * 300ms는 실제 더블탭(플랫폼 자체의 더블탭 창은 대략 300ms까지 이어진다)보다 위이면서도, 의도적이고
 * 별개인 두 번째 탭이 결코 삼켜지지 않을 만큼 충분히 짧다.
 */
private val SingleClickInterval = 300.milliseconds

/**
 * `singleClick = true`인 호출부가 마지막으로 받아들인 탭이 언제였는지를 나타내며, 앱 안의 그런 모든
 * 호출부가 공유한다.
 *
 * 호출부마다가 아니라 의도적으로 앱 전역이다. 이것이 막기 위해 존재하는 버그는 *이중 내비게이션* —
 * 같은 프레임에 탭된 서로 다른 두 리스트 행이 각각 대상 화면을 push하는 것 — 이며, 행마다 기억된
 * 타임스탬프는 다른 행의 탭을 볼 수 없다. 유일하게 그것을 볼 수 있는 것이 하나의 공유된 마크다.
 *
 * 이를 지키는 락은 없으며, 필요하지도 않다: Compose는 컴포지션의 단일 applier 스레드에서 클릭
 * 콜백을 전달하므로 직렬화해야 할 동시 접근이 결코 없다. 여기에 뮤텍스를 두면 스레딩 모델이 이미
 * 배제하는 경합으로부터 보호하기 위해 모든 탭마다 동기화 비용을 치르게 될 것이고, 어차피
 * `kotlin.synchronized`는 `commonMain`에서 사용할 수 없다.
 */
private var lastAcceptedClickMark: TimeMark? = null

/**
 * 앱 전역 단일 클릭 가드를 감안할 때, 지금 도착한 탭이 전달되어야 하는지를 결정한다.
 *
 * @return 탭이 받아들여지면 true이며, 이때 새 기준점으로도 기록된다. 이전에 받아들여진 탭의
 * [SingleClickInterval] 안에 들어와 버려야 하면 false.
 */
private fun acceptSingleClick(): Boolean {
    val mark = lastAcceptedClickMark
    if (mark != null && mark.elapsedNow() < SingleClickInterval) return false
    lastAcceptedClickMark = TimeSource.Monotonic.markNow()
    return true
}

/**
 * 무언가를 탭 가능하게 만드는 앱의 단일 진입점이며, 리플 피드백이 어디서나 똑같이 보이는 이유다.
 *
 * 앱의 모든 상호작용 서피스는 `clickable`을 직접 호출하는 대신 이 modifier를 거친다. 리플 계약은
 * 그것을 적용하는 장소가 하나뿐일 때만 강제할 수 있기 때문이다. 그 계약은 이렇다: **리플은 컴포넌트의
 * 내부 패딩을 덮으며, 결코 외부 간격을 덮지 않는다.** 구체적으로, 호출자가 작성하는 modifier 체인은
 * 다음과 같다
 *
 * 1. 크기 또는 채우기 경계,
 * 2. indication을 붙이기 전에 [shape]로 클립하는 이 modifier,
 * 3. 컴포넌트 자체 콘텐츠 인셋을 위한 `padding`,
 * 4. 자식 레이아웃.
 *
 * `padding`이 이 modifier 뒤에 오므로 패딩된 영역은 리플 안에 있다. 클립이 이 modifier 안에서
 * 일어나므로 리플은 결코 보이는 모양을 벗어나 번지지 않는다. 이 modifier *앞에* 놓인 `padding`은
 * 터치와 리플 영역을 깎아 버릴 것이며, 이것이 바로 이 API가 실수로도 작성할 수 없게 만들기 위해
 * 존재하는 순서 실수다.
 *
 * 최소 터치 타깃은 의도적으로 여기서 처리하지 *않는다*. 그것은 1단계, 즉 경계에 속한다: 전체 너비
 * 행은 `heightIn(min = rowHeight)`로 커지는 반면, 촘촘한 컨트롤은 보이는 indication을 키우지 않고
 * 히트 영역만 확장한다. 이 modifier 안에서 48dp를 강제하면 모든 작은 컨트롤의 리플이 그 터치
 * 영역과 함께 부풀어 버릴 것이다.
 *
 * @receiver 클릭 처리를 붙일, 이미 크기 경계를 가지고 있는 modifier 체인.
 * @param onClick 요소가 탭될 때 호출된다.
 * @param shape 리플이 클립될 윤곽. 이미 클립된 부모 안에 있거나, 전체 너비 리스트 행처럼 진짜로
 * 직사각형 영역을 채우는 요소에는 null을 전달한다 — 그런 곳에 shape를 전달하면 정사각형 행에 대해
 * 리플이 둥글게 잘려 버린다.
 * @param enabled 요소가 입력에 아예 반응할지 여부. false이면 단순히 탭을 무시하는 것을 넘어 클릭
 * semantics도 제거한다.
 * @param role 이 요소에 대해 알려지는 접근성 역할.
 * @param onClickLabel 탭이 무엇을 하는지에 대한 접근성 설명.
 * @param onLongClick 롱프레스 시 호출된다. non-null이면 요소는 `combinedClickable`을 사용하여 롱프레스와
 * 탭을 구별되게 유지한다. null이면 롱프레스는 위에 있는 어떤 제스처 핸들러로든 그대로 통과하는데, 이는
 * 팬 가능한 서피스 안 행이 원하는 동작이다.
 * @param onLongClickLabel 롱프레스에 대한 접근성 설명으로, [onLongClick]과 함께일 때만 의미가 있다.
 * @param singleClick 이 호출부가 [lastAcceptedClickMark]에 설명된 앱 전역 가드에 참여할지 여부.
 * 중복 호출이 실제로 해로운 내비게이션과 일회성 변경에는 켠다. 사용자가 정당하게 빠르게 연속으로
 * 탭할 수 있는 것 — 여러 리스트 선택 토글, 값 단계 조정 — 에는 꺼 둔다. 이 가드는 공유되며 *다른*
 * 요소들에 대한 두 번의 의도적인 탭 중 두 번째를 삼켜 버릴 것이기 때문이다.
 * @param interactionSource 눌림을 보고할 소스. 컴포넌트가 자신의 눌린 상태를 색상에 반영할 때
 * 전달하여, 시각적 표현과 indication이 같은 스트림을 관찰하게 한다. null이면 내부에서 하나가
 * remember된다.
 * @return 클립, indication, 클릭 처리가 계약된 순서로 적용된 리시버.
 */
@Composable
fun Modifier.teddClickable(
    onClick: () -> Unit,
    shape: Shape? = null,
    enabled: Boolean = true,
    role: Role? = null,
    onClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    singleClick: Boolean = false,
    interactionSource: MutableInteractionSource? = null,
): Modifier = teddInteraction(
    shape = shape,
    interactionSource = interactionSource,
    rippleEnabled = true,
) { source, indication ->
    val guardedClick: () -> Unit = { if (!singleClick || acceptSingleClick()) onClick() }

    if (onLongClick == null) {
        clickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            interactionSource = source,
            indication = indication,
            onClick = guardedClick,
        )
    } else {
        combinedClickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            interactionSource = source,
            indication = indication,
            onClick = guardedClick,
        )
    }
}

/**
 * [teddClickable]의 on/off 대응물로, 같은 리플 및 순서 계약을 갖는다.
 *
 * 전체 너비가 값을 토글하는 설정 행에서 쓰인다. 행이 토글 semantics를 소유하고, 그 안의 시각적
 * 컨트롤에는 null change handler가 전달되어, 행은 경쟁하는 작은 타깃을 담은 큰 타깃이 아니라 하나의
 * 접근성 타깃이 된다.
 *
 * @receiver 토글 처리를 붙일, 이미 크기 경계를 가지고 있는 modifier 체인.
 * @param value 접근성 서비스에 체크 상태로 보고되는 현재 상태.
 * @param onValueChange 요소가 이동해야 할 상태와 함께 호출된다.
 * @param shape 리플이 클립될 윤곽. 전체 너비 행에는 null.
 * @param enabled 요소가 입력에 반응할지 여부.
 * @param role 접근성 역할로, 보통 `Role.Switch`나 `Role.Checkbox`다 — 이것이 스크린 리더에게 행이
 * 어떤 종류의 컨트롤인지 알려 준다.
 * @param interactionSource 눌림을 보고할 소스. null이면 내부에서 하나가 remember된다.
 * @return 클립, indication, 토글 처리가 계약된 순서로 적용된 리시버.
 */
@Composable
fun Modifier.teddToggleable(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    shape: Shape? = null,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier = teddInteraction(
    shape = shape,
    interactionSource = interactionSource,
    rippleEnabled = true,
) { source, indication ->
    toggleable(
        value = value,
        enabled = enabled,
        role = role,
        interactionSource = source,
        indication = indication,
        onValueChange = onValueChange,
    )
}

/**
 * [teddClickable]의 상호 배타적 선택 대응물로, 같은 리플 및 순서 계약을 갖는다.
 *
 * 라디오 행과 선택 가능한 칩에서 쓰인다. 이 modifier 자체는 항상 자신의 selected 상태만 보고한다;
 * 집합 관계 semantics — 스크린 리더가 그냥 "selected"가 아니라 "3개 중 2번째"라고 알리는 것 — 는
 * 호출자가 `isSelectableGroup = true`로 행들을
 * [com.tedd.teddreader.core.ui.component.TeddOptionGroup]으로 감쌀 때만 더해지며, 이것이 실제로
 * 감싸는 column에 `Modifier.selectableGroup`을 적용하는 부분이다. 감싸지 않았거나 그 플래그 없이
 * 감쌌다면, 각 행은 여전히 선택 여부를 알리지만 스크린 리더는 선택지가 몇 개 있는지 또는 이것이 몇
 * 번째 위치인지 말할 방법이 없다.
 *
 * @receiver 선택 처리를 붙일, 이미 크기 경계를 가지고 있는 modifier 체인.
 * @param selected 이 요소가 선택된 것인지 여부로, selected semantics로 보고된다.
 * @param onClick 요소가 선택될 때 호출된다. 이미 선택된 요소를 선택해도 여전히 발생하는데, 호출자가
 * 이를 재확인으로 정당하게 취급할 수 있기 때문이다.
 * @param shape 리플이 클립될 윤곽. 전체 너비 행에는 null, 칩에는 pill 모양.
 * @param enabled 요소가 입력에 반응할지 여부.
 * @param role 이 요소에 대해 알려지는 접근성 역할.
 * @param interactionSource 눌림을 보고할 소스. null이면 내부에서 하나가 remember된다.
 * @return 클립, indication, 선택 처리가 계약된 순서로 적용된 리시버.
 */
@Composable
fun Modifier.teddSelectable(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape? = null,
    enabled: Boolean = true,
    role: Role? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier = teddInteraction(
    shape = shape,
    interactionSource = interactionSource,
    rippleEnabled = true,
) { source, indication ->
    selectable(
        selected = selected,
        enabled = enabled,
        role = role,
        interactionSource = source,
        indication = indication,
        onClick = onClick,
    )
}

/**
 * interaction source와 indication을 한 번 해석하고, [shape]로 클립한 뒤, 호출자가 필요로 하는
 * foundation interaction modifier가 무엇이든 둘 다 넘긴다.
 *
 * 클립-후-indication 순서와 리플 색상이 정확히 한 곳에만 살도록 존재한다. 위의 공개 modifier들이
 * 각자 이를 반복했다면, 그 계약은 서로 어긋날 수 있는 네 벌의 사본이 되었을 것이며, 이것이 바로 이
 * 파일이 끝내기 위해 작성된 상황이다.
 *
 * @receiver interaction이 붙는 modifier 체인.
 * @param shape indication을 붙이기 전에 클립할 윤곽, 또는 리시버 자체의 경계를 리플 경계로 남기려면
 * null.
 * @param interactionSource 호출자의 소스, 또는 여기서 하나를 remember하려면 null.
 * @param rippleEnabled 리플 indication을 제공할지 아니면 아예 없앨지 여부.
 * @param attach 해석된 소스와 indication으로부터 실제 interaction modifier를 만든다.
 * @return 요청되었다면 클립되고, 그 위에 [attach]의 modifier가 적용된 리시버.
 */
@Composable
private inline fun Modifier.teddInteraction(
    shape: Shape?,
    interactionSource: MutableInteractionSource?,
    rippleEnabled: Boolean,
    attach: Modifier.(MutableInteractionSource, androidx.compose.foundation.Indication?) -> Modifier,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val indication = if (rippleEnabled) ripple(color = teddReaderColors().ripple) else null
    val clipped = if (shape != null) clip(shape) else this
    return clipped.attach(source, indication)
}

/**
 * 앱의 컨테이너 처리 — 깊이감, 테두리, 채우기, 그리고 자식을 모서리 안에 가두는 클립 — 을 이미
 * 존재하는 레이아웃 위에 modifier로 그린다.
 *
 * 콘텐츠가 이미 자체 레이아웃 루트를 가지고 있을 때는 카드 컴포저블로 감싸는 것보다 이쪽이
 * 선호된다. 그런 래퍼는 이 네 modifier를 나르는 것 외에 아무 일도 하지 않는 노드를 추가하게 되기
 * 때문이다. 그리드 셀, 오버레이 `Box`, 또는 페이저 페이지는 여기서 카드 외양을 얻는다; 자식을 진짜로
 * 배치해야 하는 컨테이너만 카드 컴포저블을 사용한다.
 *
 * 마지막의 클립은 빠뜨리기 쉽고 놓치면 대가가 큰 부분이다: 이것이 없으면 자식 이미지나 채워진 행이
 * 방금 테두리가 그린 둥근 모서리 위에 그려져 버려, 그 자식 하나만 모서리가 깨진 것처럼 보인다.
 *
 * 채우기는 테두리 뒤가 아니라 앞에 온다. modifier 체인에서는 앞선 항목이 바깥쪽이며 먼저 그려지므로,
 * 채우기보다 앞서 선언된 테두리는 그 위에 덮여 그려진다 — 1dp 획은 모양의 가장자리에 놓이므로 그
 * 절반이 채우기 아래로 사라져, 윤곽선이 테두리가 아니라 일관되지 않게 렌더링되는 머리카락 굵기 선처럼
 * 보인다. 이것이 이식된 원본 구현은 순서를 반대로 두었다; 그 순서만은 그대로 옮겨 오지 않았다.
 *
 * @receiver 컨테이너 처리를 받는 레이아웃의 modifier 체인.
 * @param shape 컨테이너 윤곽으로, 네 레이어 모두에 쓰여 깊이감, 테두리, 채우기, 클립이 서로
 * 일치하게 한다.
 * @param elevation 그림자 깊이. 평평한 컨테이너에는 `0.dp`를 전달하며, 이는 아무 작업도 건너뛰지
 * 않으면서 눈에 보이는 그림자를 만들지 않는다.
 * @param borderWidth 테두리 두께. 기기 픽셀 하나는 간격 스케일의 한 단계가 아니므로
 * `TeddReaderSpacing`이 아니라 [teddReaderStroke]의 머리카락 굵기 토큰을 기본값으로 사용한다.
 * 테두리가 없으려면 `0.dp`를 전달한다.
 * @param borderColor 테두리 색상. 기본값은 팔레트의 은은한 컨테이너 윤곽선이다.
 * @param backgroundColor 채우기. 기본값은 팔레트의 서피스다.
 * @return 그림자, 테두리, 배경, 클립이 그 순서로 적용된 리시버.
 */
@Composable
fun Modifier.teddSurface(
    shape: Shape = teddReaderShapes().medium,
    elevation: Dp = 0.dp,
    borderWidth: Dp = teddReaderStroke().hairline,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
): Modifier {
    val colors = teddReaderColors()

    return this
        .shadow(elevation = elevation, shape = shape, spotColor = colors.shadow, ambientColor = colors.shadow)
        .background(color = backgroundColor ?: colors.surface, shape = shape)
        .border(BorderStroke(borderWidth, borderColor ?: colors.outlineSubtle), shape)
        .clip(shape)
}

/**
 * 사용자가 주변 배경을 탭하면 텍스트 필드로부터 포커스를 되돌려 놓는다.
 *
 * 화면의 배경 레이어나 폼의 루트에 적용된다. 이것이 없으면 검색이나 메모 필드가 띄운 소프트
 * 키보드가 사용자가 시스템 뒤로 가기 제스처를 찾을 때까지 계속 떠 있게 되는데, 빈 공간을 탭하는
 * 것은 `TextField`가 듣는 일이 아니기 때문이다.
 *
 * 이것은 탭 감지기를 설치하므로, 자체 제스처를 소유한 영역 위에는 두면 안 된다 — 특히 리더 페이지가
 * 그렇다. 그곳에서는 같은 탭을 두고 페이지 내비게이션과 경쟁하게 될 것이다.
 *
 * @receiver 배경 레이어의 modifier 체인.
 * @param focusManager 포커스가 지워질 매니저로, 보통 `LocalFocusManager.current`다.
 * @param enabled 감지기를 아예 설치할지 여부. false이면 화면이 modifier 체인을 재구성하지 않고도
 * 동작을 비활성화할 수 있도록 리시버를 그대로 반환한다.
 * @param force 포커스를 가진 필드가 명시적으로 포커스를 캡처한 경우에도 포커스를 지울지 여부. true면
 * 필드가 의도적으로 붙들고 있는 포커스도 해제한다.
 * @return [enabled]일 때 배경 탭 감지기가 설치된 리시버.
 */
fun Modifier.clearFocusOnBackgroundTap(
    focusManager: FocusManager,
    enabled: Boolean = true,
    force: Boolean = false,
): Modifier {
    if (!enabled) return this

    return pointerInput(focusManager, force) {
        detectTapGestures(onTap = { focusManager.clearFocus(force = force) })
    }
}
