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
 * Android implementation of the reader's battery percentage probe: reads the device's charge
 * level through the system [BatteryManager] service and republishes it every
 * [BatteryRefreshIntervalMillis], so the reader's status footer stays close to the system's own
 * battery indicator without its caller having to poll for it itself. The iOS actual instead reads
 * `UIDevice.batteryLevel` and must explicitly enable/disable battery monitoring around the read.
 *
 * @return The device's current battery charge, 0 to 100, or null if the platform property is not
 * available.
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
 * Reads this [BatteryManager]'s current charge level as a percentage, discarding whatever value
 * the platform reports when it does not actually know the battery level, rather than surfacing a
 * nonsense percentage to the reader's status footer.
 *
 * @receiver The system battery service to query.
 * @return The battery charge, 0 to 100, or null if the reported value falls outside that range.
 */
private fun BatteryManager.currentPercent(): Int? =
    getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }

/**
 * How often [rememberReaderBatteryPercent] re-reads the battery level, in milliseconds. Battery
 * charge changes slowly enough that a 30-second poll keeps the status footer accurate without
 * waking up on every recomposition.
 */
private const val BatteryRefreshIntervalMillis = 30_000L
