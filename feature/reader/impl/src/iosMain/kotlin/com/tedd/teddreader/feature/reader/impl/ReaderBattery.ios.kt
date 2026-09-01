package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import platform.UIKit.UIDevice
import kotlin.math.roundToInt

/**
 * iOS의 해법은 `UIDevice.currentDevice.batteryLevel`을 읽어, Android actual과 마찬가지로
 * [BatteryRefreshIntervalMillis]마다 다시 발행하는 것이다. 항상 사용할 수 있는 Android의 `BatteryManager`
 * 서비스와 달리 `UIDevice`는 자체 `batteryMonitoringEnabled` 플래그가 설정되어 있는 동안에만 실제 배터리
 * 잔량을 보고하므로, 이 composable이 살아 있는 동안에는 그 플래그를 켜 두었다가 이전 값으로 복원한다 —
 * 리더 화면이 한 번 열렸다는 이유로 앱이 살아 있는 나머지 기간 내내 모니터링을 켜 둔 채로 두지 않기 위해서다.
 *
 * @return 기기의 현재 배터리 충전량(0~100), `batteryLevel`이 아직 유효한 값을 보고하지 않았다면(첫 관측 전에는
 *   음수 값) null.
 */
@Composable
internal actual fun rememberReaderBatteryPercent(): Int? {
    val device = UIDevice.currentDevice
    val wasMonitoring = remember(device) { device.batteryMonitoringEnabled }
    var percent by remember(device) { mutableStateOf<Int?>(null) }

    DisposableEffect(device) {
        device.batteryMonitoringEnabled = true
        onDispose { device.batteryMonitoringEnabled = wasMonitoring }
    }
    LaunchedEffect(device) {
        device.batteryMonitoringEnabled = true
        while (true) {
            percent = device.batteryLevel
                .takeIf { it >= 0f }
                ?.times(100f)
                ?.roundToInt()
                ?.coerceIn(0, 100)
            delay(BatteryRefreshIntervalMillis)
        }
    }
    return percent
}

/**
 * [rememberReaderBatteryPercent]가 `batteryLevel`을 다시 읽는 주기(밀리초)로, Android actual이
 * `BatteryManager`를 폴링하는 것과 같은 주기를 사용해 플랫폼과 무관하게 상태 표시줄의 배터리 갱신 빈도를
 * 하나로 맞춘다. 배터리 충전량은 변화가 충분히 느려서 30초 폴링만으로도 매 recomposition마다 이 composable을
 * 깨우지 않고 상태 표시줄을 정확하게 유지할 수 있다.
 */
private const val BatteryRefreshIntervalMillis = 30_000L
