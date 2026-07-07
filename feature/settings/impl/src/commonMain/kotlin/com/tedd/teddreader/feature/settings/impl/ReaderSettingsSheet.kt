package com.tedd.teddreader.feature.settings.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.reader.ReaderOptionPreview

@Composable
fun ReaderSettingsSheet(
    uiState: ReaderSettingsUiState,
) {
    val spacing = teddReaderSpacing()
    if (uiState.isLoading) {
        TeddLoadingIndicator(message = "Loading reader settings")
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.medium)) {
        ReaderOptionPreview(
            style = uiState.style,
            description = "Current typography and palette preview.",
            modifier = Modifier.fillMaxWidth(),
        )

        TeddOptionGroup(title = "Typography") {
            SettingSummaryRow(
                title = "Font size",
                value = "${uiState.style.fontSizeSp.toInt()}sp",
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = "Line height",
                value = uiState.style.lineHeightMultiplier.toString(),
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = "Font family",
                value = uiState.style.fontFamilyName ?: "Sans",
            )
        }

        TeddOptionGroup(title = "Reading flow") {
            SettingSummaryRow(
                title = "Page turn",
                value = uiState.pageTurnMode.name.lowercase(),
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = "Animation",
                value = uiState.pageAnimation.name.lowercase(),
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = "Auto-scroll",
                value = if (uiState.autoScrollConfig.enabled) {
                    "${uiState.autoScrollConfig.mode.name.lowercase()} · ${uiState.autoScrollConfig.speed}x"
                } else {
                    "Off"
                },
            )
        }
    }
}

@Composable
private fun SettingSummaryRow(
    title: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
    )
}

@Preview
@Composable
private fun ReaderSettingsSheetPreview() {
    TeddReaderTheme {
        ReaderSettingsSheet(uiState = ReaderSettingsUiState(isLoading = false))
    }
}
