package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * A [LoadingIndicator] with an optional caption underneath (e.g. "Loading document"), so any screen
 * that needs to explain what it is waiting for does not have to hand-assemble a [Column] pairing the
 * spinner with a [Text].
 *
 * @param modifier Modifier applied to the indicator's root [Column].
 * @param message Caption shown under the spinner in a muted color; omitted when null.
 */
@Composable
fun TeddLoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        LoadingIndicator()
        if (message != null) {
            Text(
                text = message,
                style = typography.settingDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * [TeddLoadingIndicator] centered in the full available space, for screens that are entirely blocked
 * on a load (e.g. opening a document) rather than showing a spinner inline among other content.
 *
 * @param modifier Modifier applied before `fillMaxSize()` is added.
 * @param message Caption shown under the spinner in a muted color; omitted when null.
 */
@Composable
fun TeddFullScreenLoadingIndicator(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        TeddLoadingIndicator(message = message)
    }
}

/** Compose preview rendering [TeddLoadingIndicator] with a message and extra padding. */
@Preview
@Composable
private fun TeddLoadingIndicatorPreview() {
    TeddPreviewSurface {
        TeddLoadingIndicator(
            modifier = Modifier.padding(24.dp),
            message = "Loading document",
        )
    }
}
