package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Composable

/**
 * 리더 상태 표시줄의 배터리 값으로, 이를 호출하는 composable이 살아 있는 동안 주기적으로 갱신된다. 각
 * 플랫폼은 자신이 가진 배터리 API로 응답하며, 불필요하게 자주 깨어나지 않으면서도 상태 표시줄이 시스템 자체
 * 표시와 가깝게 유지되는 주기로 폴링한다(각 플랫폼 actual의 `BatteryRefreshIntervalMillis` 참고).
 *
 * @return 기기의 현재 배터리 충전량(0~100), 플랫폼이 지금 당장 보고할 수 없으면 null.
 */
@Composable
internal expect fun rememberReaderBatteryPercent(): Int?
