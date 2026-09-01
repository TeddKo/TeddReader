package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.teddToggleable

/**
 * 제목/설명과 후행 [Checkbox]를 짝짓는 설정 행으로, 체크박스 글리프만이 아니라 행 전체가 탭
 * 타깃이다 — 행 자체가 `role = Role.Checkbox`로 [teddToggleable]을 가지고, [Checkbox]에는
 * `onCheckedChange = null`이 전달되어 시각적으로는 렌더링되지만 그 자체의 더 작은 탭 타깃은 등록하지
 * 않는다. 체크박스 경우에 대해 [TeddSwitchRow], [TeddRadioRow]와 동일한 패턴이다. [Checkbox] 자체는
 * 새로 만들지 않고 그대로 유지한다 — `onCheckedChange`가 null이면 이미 자체 리플이 제거되므로 —
 * `colors`만 주변 Material 스킴 대신 이 앱의 토큰에 고정한다.
 *
 * @param title [teddReaderTypography]의 `settingTitle` 스타일로 표시되는 행의 주요 텍스트.
 * @param checked 체크박스를 체크된 상태로 그릴지 여부.
 * @param onCheckedChange 행이 탭될 때 새 체크 상태와 함께 호출된다. [enabled]가 false인 동안에는
 * 호출되지 않는다.
 * @param modifier 행 루트에 적용되는 modifier.
 * @param description [title] 아래 흐린 색상으로 표시되는 두 번째 줄. null이면 생략된다.
 * @param enabled 행이 탭에 반응할지 여부. false이면 체크박스도 비활성 색상으로 전환된다.
 * @param contentPadding 행 가장자리와 콘텐츠 사이의 패딩. null이면 테마의 screenPadding/small
 * 조합을 사용하여, 행의 가로 인셋이 그 안쪽 4dp에 놓이는 대신 [TeddSection]의 화면 패딩과
 * 맞춰진다.
 */
@Composable
fun TeddCheckboxRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.screenPadding,
        vertical = spacing.small,
    )
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    CompositionLocalProvider(LocalContentColor provides teddReaderColors().onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = spacing.rowHeight)
                .background(colors.surface)
                .teddToggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    enabled = enabled,
                    role = Role.Checkbox,
                )
                .padding(resolvedContentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TeddText(text = title, style = typography.settingTitle)
                if (description != null) {
                    TeddText(
                        text = description,
                        style = typography.settingDescription,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.primary,
                    uncheckedColor = colors.onSurfaceVariant,
                    checkmarkColor = colors.onPrimary,
                ),
            )
        }
    }
}

/** 제목과 설명을 갖춘 체크 해제 상태로 [TeddCheckboxRow]를 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddCheckboxRowPreview() {
    TeddPreviewSurface {
        TeddCheckboxRow(
            title = "Show page numbers",
            description = "Display current and total page counts.",
            checked = false,
            onCheckedChange = {},
        )
    }
}
