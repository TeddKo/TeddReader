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

private fun BatteryManager.currentPercent(): Int? =
    getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

private const val BatteryRefreshIntervalMillis = 30_000L
