package com.tedd.teddreader.core.designsystem

import androidx.compose.runtime.Immutable

/**
 * 화면마다 전환 시간이 일관되도록 앱에서 사용하는 세 가지 애니메이션 길이입니다.
 *
 * `Duration`이 아니라 밀리초를 사용합니다. 모든 Compose 애니메이션 사양이 밀리초를 `Int`로 받으며, 각
 * 호출 지점에서 변환하면 불필요한 코드만 생기기 때문입니다.
 *
 * @property shortDurationMs 손가락이 이미 닿아 있는 컨트롤의 상태 변경 시간입니다.
 * @property mediumDurationMs 한 화면 안에서 상태가 전환될 때 사용하는 기본 시간입니다.
 * @property longDurationMs 시트처럼 표면 전체가 이동하는 전환 시간입니다.
 */
@Immutable
data class TeddReaderMotion(
    val shortDurationMs: Int = 120,
    val mediumDurationMs: Int = 200,
    val longDurationMs: Int = 300,
)

/** 호출자가 재정의하지 않을 때 테마가 설치하는 모션 척도입니다. */
val DefaultTeddReaderMotion = TeddReaderMotion()
