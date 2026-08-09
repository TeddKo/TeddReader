package com.tedd.teddreader.feature.settings.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.reader.ReaderOptionPreview
import kotlin.math.roundToInt

@Composable
fun ReaderSettingsSheet(
    uiState: ReaderSettingsUiState,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    if (uiState.isLoading) {
        TeddLoadingIndicator(
            message = "Loading reader settings",
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.medium),
    ) {
        ReaderOptionPreview(
            style = uiState.style,
            description = "Current reading appearance.",
            modifier = Modifier.fillMaxWidth(),
        )

        TeddOptionGroup(
            title = "Reading appearance",
            description = "Text choices that affect comfort and readability.",
        ) {
            SettingSummaryRow(
                title = "Font size",
                value = "${uiState.style.fontSizeSp.roundToInt()} sp",
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = "Line spacing",
                value = "${uiState.style.lineHeightMultiplier}×",
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = "Font",
                value = uiState.style.fontFamilyName ?: "System sans",
            )
        }

        TeddOptionGroup(
            title = "Page movement",
            description = "How pages move when you read and navigate.",
        ) {
            SettingSummaryRow(
                title = "Page turn",
                value = uiState.pageTurnMode.displayName(),
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = "Animation",
                value = uiState.pageAnimation.displayName(),
            )
        }

        TeddOptionGroup(
            title = "Hands-free reading",
            description = "Automatic movement when you want the document to keep going.",
        ) {
            SettingSummaryRow(
                title = "Auto-scroll",
                value = uiState.autoScrollConfig.summary(),
            )
        }
    }
}

@Composable
private fun SettingSummaryRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.medium, vertical = spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        Text(
            text = title,
            style = typography.settingTitle,
        )
        Text(
            text = value,
            style = typography.settingDescription,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun PageTurnMode.displayName(): String = when (this) {
    PageTurnMode.HORIZONTAL -> "Horizontal"
    PageTurnMode.VERTICAL -> "Vertical"
    PageTurnMode.CONTINUOUS -> "Continuous"
}

private fun PageAnimation.displayName(): String = when (this) {
    PageAnimation.NONE -> "None"
    PageAnimation.SLIDE -> "Slide"
    PageAnimation.FADE -> "Fade"
    PageAnimation.SCROLL -> "Scroll"
    PageAnimation.BOOK_CURL -> "Book curl"
    PageAnimation.SHEET_FLIP -> "Slide"
    PageAnimation.FLUID_PAGER -> "Fluid pager"
    PageAnimation.CURL_PAGER -> "Curl pager"
    PageAnimation.CIRCLE_REVEAL -> "Circle reveal"
    PageAnimation.MOVIE_CAROUSEL -> "Movie carousel"
    PageAnimation.PAGE_FLIP -> "Page flip"
}


private fun com.tedd.teddreader.core.common.model.AutoScrollConfig.summary(): String {
    if (!enabled) return "Off"
    val modeLabel = when (mode) {
        AutoScrollMode.PIXEL -> "Smooth"
        AutoScrollMode.PAGE -> "Page by page"
    }
    return "$modeLabel · ${speed}×"
}

@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Composable
private fun ReaderSettingsSheetPreview() {
    TeddReaderTheme {
        ReaderSettingsSheet(uiState = ReaderSettingsUiState(isLoading = false))
    }
}
