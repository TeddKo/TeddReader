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
import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollMode
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddLoadingIndicator
import com.tedd.teddreader.core.ui.component.TeddOptionGroup
import com.tedd.teddreader.core.ui.component.TeddRadioRow
import com.tedd.teddreader.core.ui.reader.ReaderOptionPreview
import com.tedd.teddreader.core.ui.teddString
import kotlin.math.roundToInt

@Composable
fun ReaderSettingsSheet(
    uiState: ReaderSettingsUiState,
    onAppLanguageChange: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    if (uiState.isLoading) {
        TeddLoadingIndicator(
            message = teddString("Loading reader settings", "리더 설정을 불러오는 중"),
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
            title = teddString("Reader preview", "리더 미리보기"),
            description = teddString("Current reading appearance.", "현재 읽기 모양입니다."),
            modifier = Modifier.fillMaxWidth(),
        )

        TeddOptionGroup(
            title = teddString("App language", "앱 언어"),
            description = teddString(
                "Choose the language used across the app.",
                "앱 전체에서 사용할 언어를 선택하세요.",
            ),
        ) {
            AppLanguage.entries.forEach { language ->
                TeddRadioRow(
                    title = language.displayName(),
                    selected = uiState.appLanguage == language,
                    onClick = { onAppLanguageChange(language) },
                )
            }
        }

        TeddOptionGroup(
            title = teddString("Reading appearance", "읽기 모양"),
            description = teddString(
                "Text choices that affect comfort and readability.",
                "읽기 편안함과 가독성에 영향을 주는 텍스트 설정입니다.",
            ),
        ) {
            SettingSummaryRow(
                title = teddString("Font size", "글자 크기"),
                value = "${uiState.style.fontSizeSp.roundToInt()} sp",
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = teddString("Line spacing", "줄 간격"),
                value = "${uiState.style.lineHeightMultiplier}×",
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = teddString("Font", "글꼴"),
                value = uiState.style.fontFamilyName ?: teddString("System sans", "시스템 산세리프"),
            )
        }

        TeddOptionGroup(
            title = teddString("Page movement", "페이지 이동"),
            description = teddString(
                "How pages move when you read and navigate.",
                "읽고 이동할 때 페이지가 움직이는 방식을 보여줍니다.",
            ),
        ) {
            SettingSummaryRow(
                title = teddString("Page turn", "페이지 전환"),
                value = uiState.pageTurnMode.displayName(),
            )
            HorizontalDivider()
            SettingSummaryRow(
                title = teddString("Animation", "애니메이션"),
                value = uiState.pageAnimation.displayName(),
            )
        }

        TeddOptionGroup(
            title = teddString("Hands-free reading", "핸즈프리 읽기"),
            description = teddString(
                "Automatic movement when you want the document to keep going.",
                "문서를 자동으로 계속 읽고 싶을 때의 이동 설정입니다.",
            ),
        ) {
            SettingSummaryRow(
                title = teddString("Auto-scroll", "자동 스크롤"),
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

@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> teddString("System default", "시스템 기본")
    AppLanguage.ENGLISH -> "English"
    AppLanguage.KOREAN -> "한국어"
}

@Composable
private fun PageTurnMode.displayName(): String = when (this) {
    PageTurnMode.HORIZONTAL -> teddString("Horizontal", "가로")
    PageTurnMode.VERTICAL,
    PageTurnMode.CONTINUOUS,
        -> teddString("Vertical", "세로")
}

@Composable
private fun PageAnimation.displayName(): String = when (this) {
    PageAnimation.NONE -> teddString("None", "없음")
    PageAnimation.SLIDE,
    PageAnimation.SHEET_FLIP,
        -> teddString("Slide", "슬라이드")
    PageAnimation.FADE -> teddString("Fade", "페이드")
    PageAnimation.SCROLL -> teddString("Scroll", "스크롤")
    PageAnimation.BOOK_CURL,
    PageAnimation.CURL_PAGER,
        -> teddString("Curl pager", "컬 페이지")
    PageAnimation.FLUID_PAGER -> teddString("Fluid pager", "플루이드 페이지")
    PageAnimation.CIRCLE_REVEAL -> teddString("Circle reveal", "원형 전환")
    PageAnimation.MOVIE_CAROUSEL -> teddString("Movie carousel", "무비 캐러셀")
    PageAnimation.PAGE_FLIP -> teddString("Page flip", "페이지 플립")
}

@Composable
private fun com.tedd.teddreader.core.common.model.AutoScrollConfig.summary(): String {
    if (!enabled) return teddString("Off", "끔")
    val modeLabel = when (mode) {
        AutoScrollMode.PIXEL -> teddString("Smooth", "부드럽게")
        AutoScrollMode.LINE -> teddString("Line by line", "한 줄씩")
        AutoScrollMode.PAGE -> teddString("Page by page", "페이지별")
    }
    return "$modeLabel · ${speed}×"
}

@Preview(widthDp = 280)
@Preview(widthDp = 360)
@Composable
private fun ReaderSettingsSheetPreview() {
    TeddReaderTheme {
        ReaderSettingsSheet(
            uiState = ReaderSettingsUiState(isLoading = false),
            onAppLanguageChange = {},
        )
    }
}
