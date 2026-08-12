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

private const val BatteryRefreshIntervalMillis = 30_000L
