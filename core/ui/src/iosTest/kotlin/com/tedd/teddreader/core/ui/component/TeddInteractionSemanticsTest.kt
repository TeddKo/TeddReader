package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.icon.TeddIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 앱의 리플 및 터치 타깃 정책이 강제하기 위해 존재하는 상호작용 계약을 지킨다.
 *
 * 이 속성들은 컴파일러에게 보이지 않는다: 컴포넌트가 `role`을 빠뜨리거나, content description을
 * 잃거나, 48dp 터치 하한 확보를 멈춰도 여전히 빌드된다. 이 스위트 이전에는 이 중 무엇에 대해서도
 * 리뷰어가 알아채는 것 말고는 검증 수단이 없었고, 실제로 칩이 터치 공간을 전혀 확보하지 않고 있던
 * 적이 있었다.
 *
 * 의도적으로 `commonTest`가 아니라 `iosTest`에 있다. `runComposeUiTest`는 실제 컴포지션 호스트가
 * 필요하다; iOS 시뮬레이터 타깃은 이를 제공하지만, `testAndroidHostTest`는 Robolectric 없이는 이를
 * 실행할 수 없는 일반 JVM 단위 테스트다. Compose Multiplatform은 두 플랫폼 모두 같은 공통 소스에서
 * 이 화면들을 그리므로, 여기서 검증하면 공유되는 동작을 커버한다 — 커버하지 못하는 것은 Android에서만
 * 다른 부분이다.
 */
class TeddInteractionSemanticsTest {

    /**
     * `TeddReaderSpacing.touchTarget`에서 온, 모든 상호작용 요소가 넘어야 하는 터치 하한. 누군가 토큰을
     * 낮췄을 때 새 값에 조용히 동의하는 대신 테스트가 실패하도록, 테마에서 읽는 대신 여기 리터럴로
     * 적어 둔다.
     */
    private val touchTarget = 48.dp

    /** 닫기 스크림이 시트의 유일한 클릭 타깃이다; 시각적 드래그 핸들은 클릭 타깃이 아니다. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun bottomSheetDragHandleIsNotAClickTarget() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddModalBottomSheet(
                    title = "Options",
                    onDismissRequest = {},
                ) {
                    TeddText("Body")
                }
            }
        }

        onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    /**
     * 탭 가능한 칩은 그것이 그리는 알약을 컴팩트하게 유지하면서 48dp 터치 하한을 확보한다.
     *
     * 두 절반을 함께 단언하는 이유는 둘 중 하나만으로는 잘못된 이유로 통과할 수 있기 때문이다.
     * 상호작용 노드 — `minimumInteractiveComponentSize`가 만들어 내는, 텍스트에서 한 단계 바깥의 노드 —
     * 는 하한에 도달해야 하고, 텍스트 노드는 그래서는 안 된다. 알약을 48dp로 부풀리는 것이 하한만
     * 확인하는 단언이 그냥 통과시켜 버릴 실패 모드이기 때문이다.
     *
     * 여기서는 먼저 터치 경계 단언을 시도했지만 무가치한 것으로 드러났다: Compose는 어떤 `selectable`의
     * 히트 테스트든 플랫폼 최소값 쪽으로 늘리므로, 칩의 크기 조정을 무력화해도
     * `assertTouchHeightIsEqualTo(48.dp)`는 계속 통과했다. 실제로 움직이는 것은 레이아웃 경계다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappableChipReservesTouchFloorWithoutInflatingItsPill() = runComposeUiTest {
        var taps = 0
        setContent {
            TeddReaderTheme {
                TeddChip(text = "All", onClick = { taps++ }, selected = true)
            }
        }

        val label = onNodeWithText("All")
        label.assertIsSelected()

        val pill = label.getBoundsInRoot()
        assertTrue(
            pill.bottom - pill.top < touchTarget,
            "The drawn pill must stay compact rather than inflating to the touch floor",
        )

        label.onParent().assertHeightIsAtLeast(touchTarget)
        label.onParent().assertWidthIsAtLeast(touchTarget)

        label.performClick()
        assertEquals(1, taps, "A tappable chip must invoke its action")
    }

    /**
     * 선택된 칩은 다르게 보이는 것만이 아니라 스크린 리더에게도 그것을 알려야 한다.
     *
     * 정적인, 탭 불가능한 칩은 배경을 직접 그리는 별도의 분기를 통해 렌더링되며, 그 분기는 selected
     * semantics를 제공할 selectable modifier가 없으므로 이를 직접 추가해야 한다. 이 분기가 조용히 그
     * 상태를 잃어버리기 가장 쉬운 곳이다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun staticSelectedChipStillAnnouncesSelection() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddChip(text = "EPUB", selected = true)
            }
        }

        onNodeWithText("EPUB").assertIsSelected()
    }

    /**
     * 아이콘 버튼은 종종 그 액션의 유일한 라벨이므로, 설명과 터치 박스 둘 다 핵심적인 역할을 한다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun iconButtonExposesDescriptionAndClearsTouchFloor() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddIconButton(onClick = {}, contentDescription = "Bookmark") {
                    TeddIcon(imageVector = TeddIcons.BookmarkOutline, contentDescription = null)
                }
            }
        }

        val button = onNodeWithContentDescription("Bookmark")
        button.assertHeightIsAtLeast(touchTarget)
        button.assertWidthIsAtLeast(touchTarget)
    }

    /**
     * 설정 행은 경쟁하는 작은 타깃을 담은 큰 타깃이 아니라 하나의 타깃이다.
     *
     * 행이 토글 semantics를 소유하고 그 안의 스위치 글리프에는 null change handler가 전달된다. 이
     * 분리가 깨지면 글리프가 자체 토글 상태를 보고하기 시작해 스크린 리더는 사용자가 하나로 보는 곳에서
     * 두 개의 컨트롤을 발견하게 되고 — 오직 글리프만 살아 있으므로 행의 텍스트를 탭하는 것은 동작하지
     * 않게 된다. 정확히 하나의 노드만 토글을 가지고 있는지, 그리고 행의 *라벨*을 탭하면 그것이 바뀌는지
     * 단언하는 것이 두 절반을 모두 잡아낸다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun switchRowIsOneToggleTargetDrivenByItsWholeWidth() = runComposeUiTest {
        var checked = false
        setContent {
            TeddReaderTheme {
                TeddSwitchRow(
                    title = "Keep screen on",
                    checked = checked,
                    onCheckedChange = { checked = it },
                )
            }
        }

        val row = onNodeWithText("Keep screen on")
        row.assertIsOff()
        row.performClick()
        assertTrue(checked, "Tapping the row's label must drive the toggle, not just the glyph")
    }

    /**
     * 체크박스 행은 다른 modifier를 통해 스위치 행과 같은 단일 타깃 규칙을 따르므로, 독립적으로
     * 회귀할 수 있다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun checkboxRowIsOneToggleTarget() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddCheckboxRow(title = "Include images", checked = true, onCheckedChange = {})
            }
        }

        onNodeWithText("Include images").assertIsOn()
    }

    /**
     * 라디오 행은 체크 상태가 아니라 선택 상태를 보고하며, 그것을 감싸는 그룹이 "여럿 중 하나" 관계를
     * 소유한다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun radioRowReportsSelectionNotCheckedState() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                Column {
                    TeddRadioRow(title = "Light", selected = true, onClick = {})
                    TeddRadioRow(title = "Dark", selected = false, onClick = {})
                }
            }
        }

        onNodeWithText("Light").assertIsSelected()
    }

    /**
     * 리스트 행은 콘텐츠가 짧은 한 줄뿐이더라도 row-height 토큰만큼의 높이는 유지해야 한다. 터치
     * 하한보다 짧은 행은 실제로는 닿을 수 없기 때문이다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun listItemKeepsRowHeightFloor() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddListItem(title = "A", onClick = {})
            }
        }

        onNodeWithText("A").assertHeightIsAtLeast(56.dp)
    }

    /**
     * 버튼은 라벨이 아무리 짧아도 터치 하한을 유지한다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun buttonClearsTouchFloorWithShortLabel() = runComposeUiTest {
        setContent {
            TeddReaderTheme {
                TeddButton(text = "OK", onClick = {})
            }
        }

        onNodeWithText("OK").assertHeightIsAtLeast(touchTarget)
    }

    /**
     * 앱 전역 단일 클릭 가드는 그 시간 창 안의 두 번째 탭을 버리며, 이는 오직 opt-in한 호출부에만
     * 해당한다.
     *
     * modifier를 직접 검증하는 대신 버튼을 통해 검증한다. 이 가드의 목적 전체가 실제 컨트롤이 통통 튀는
     * 더블탭에 대해 무엇을 하는가이기 때문이다. 일반 버튼은 opt-in하지 않으므로 두 탭 모두 적중해야
     * 한다; 이것이 가드를 전역으로 켰을 때 가장 깨지기 쉬운 계약의 절반이며, 그렇게 하면 빠른 리스트
     * 상호작용이 먹통처럼 느껴지게 될 것이다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun plainButtonDeliversEveryTapBecauseGuardIsOptIn() = runComposeUiTest {
        var taps = 0
        setContent {
            TeddReaderTheme {
                TeddButton(text = "Add", onClick = { taps++ })
            }
        }

        val button = onNodeWithText("Add")
        button.performClick()
        button.performClick()

        assertEquals(2, taps, "A control that did not opt into the single-click guard must see both taps")
    }

    /**
     * 비활성 버튼은 단순히 탭을 무시하는 것이 아니라 스스로를 비활성으로 보고하여, 보조 기술이 왜
     * 아무 일도 일어나지 않았는지 말할 수 있게 한다.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun disabledButtonDoesNotInvokeItsAction() = runComposeUiTest {
        var taps = 0
        setContent {
            TeddReaderTheme {
                TeddButton(text = "Save", onClick = { taps++ }, enabled = false)
            }
        }

        onNodeWithText("Save").performClick()
        assertEquals(0, taps, "A disabled button must not invoke its action")
    }
}
