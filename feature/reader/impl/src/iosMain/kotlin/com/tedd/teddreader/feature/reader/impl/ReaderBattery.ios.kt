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
 * iOS's answer: reads `UIDevice.currentDevice.batteryLevel`, republished every
 * [BatteryRefreshIntervalMillis] like the Android actual. Unlike Android's always-available
 * `BatteryManager` service, `UIDevice` only reports a real battery level while its own
 * `batteryMonitoringEnabled` flag is set, so this turns it on for as long as this composable is
 * alive and restores whatever the flag was before, rather than leaving monitoring on for the rest
 * of the app's lifetime after a reader screen happens to have opened once.
 *
 * @return the device's current battery charge, 0 to 100, or null while `batteryLevel` has not yet
 *   reported a valid reading (a negative value before the first observation).
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
 * How often [rememberReaderBatteryPercent] re-reads `batteryLevel`, in milliseconds — the same
 * cadence the Android actual polls `BatteryManager` at, so the footer refreshes battery at one
 * consistent rate regardless of platform. Battery charge changes slowly enough that a 30-second
 * poll keeps the status footer accurate without waking this composable up on every recomposition.
 */
private const val BatteryRefreshIntervalMillis = 30_000L
