package com.tedd.teddreader.feature.reader.impl

import android.content.Context
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * 리더 배터리 잔량 조회의 Android 구현이다. 시스템 [BatteryManager] 서비스를 통해 기기의 충전 상태를 읽고
 * [BatteryRefreshIntervalMillis]마다 다시 발행하여, 호출자가 직접 폴링하지 않아도 리더 상태 표시줄이 시스템 자체
 * 배터리 표시와 가깝게 유지되도록 한다. iOS actual은 대신 `UIDevice.batteryLevel`을 읽으며, 읽는 동안 배터리
 * 모니터링을 명시적으로 켜고 꺼야 한다.
 *
 * @return 기기의 현재 배터리 충전량(0~100), 플랫폼 속성을 사용할 수 없으면 null.
 */
@Composable
internal actual fun rememberReaderBatteryPercent(): Int? {
    val context = LocalContext.current
    val batteryManager = remember(context) {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    var percent by remember(batteryManager) { mutableStateOf(batteryManager.currentPercent()) }

    LaunchedEffect(batteryManager) {
        while (true) {
            percent = batteryManager.currentPercent()
            delay(BatteryRefreshIntervalMillis)
        }
    }
    return percent
}

/**
 * 이 [BatteryManager]의 현재 충전량을 백분율로 읽는다. 플랫폼이 배터리 잔량을 실제로 알지 못할 때 보고하는 값은
 * 버리고, 리더 상태 표시줄에 말이 안 되는 퍼센트가 노출되지 않게 한다.
 *
 * @receiver 조회할 시스템 배터리 서비스.
 * @return 배터리 충전량(0~100), 보고된 값이 이 범위를 벗어나면 null.
 */
private fun BatteryManager.currentPercent(): Int? =
    getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

/**
 * [rememberReaderBatteryPercent]가 배터리 잔량을 다시 읽는 주기(밀리초). 배터리 충전량은 변화가 충분히 느려서
 * 30초 폴링만으로도 매 recomposition마다 깨어나지 않고 상태 표시줄을 정확하게 유지할 수 있다.
 */
private const val BatteryRefreshIntervalMillis = 30_000L
