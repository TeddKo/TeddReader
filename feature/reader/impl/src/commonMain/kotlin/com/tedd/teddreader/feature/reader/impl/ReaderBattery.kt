package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Composable

/**
 * The reader status footer's battery reading, refreshed periodically for as long as the composable
 * calling this stays alive. Each platform answers with whatever battery API it has, polling at a
 * cadence that keeps the footer close to the system's own indicator without waking up needlessly
 * often (see the platform actuals' own `BatteryRefreshIntervalMillis`).
 *
 * @return the device's current battery charge, 0 to 100, or null when the platform cannot report
 *   one right now.
 */
@Composable
internal expect fun rememberReaderBatteryPercent(): Int?
