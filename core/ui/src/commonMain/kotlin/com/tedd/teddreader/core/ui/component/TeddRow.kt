package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.teddClickable

/**
 * 선택적 구분선이 있는, 탭 가능한 두 줄짜리 리스트 행 — 설정 화면, 문서 목록, 그리고 제목, 선택적
 * 보조 줄, 선택적 leading/trailing 콘텐츠가 필요한 메뉴들의 공유 빌딩 블록으로, 모두 동일한 56dp 최소
 * 행 높이, 콘텐츠 패딩, 하단 머리카락 굵기 처리로 감싸져 있다. 이런 화면들이 각자 [Row]와 수동
 * [drawBehind] 구분선을 조립하고, 행에 탭 외에 롱프레스 액션(예: 컨텍스트 메뉴)이 필요할 때마다 같은
 * 클릭/롱클릭 분기를 다시 만들지 않아도 되도록 존재한다. 이제 그 분기는 단지 [teddClickable]이
 * 붙어 있는지 여부일 뿐이다: [onLongClick]이 null인지에 따라 `clickable`이나 `combinedClickable`을
 * 스스로 선택하므로, 이 행은 애초에 상호작용 가능한지만 결정하면 된다.
 *
 * @param title [teddReaderTypography]의 `settingTitle` 스타일로 표시되며 2줄로 잘리는, 행의 주요
 * 텍스트.
 * @param modifier 행 루트에 적용되는 modifier.
 * @param supportingText [title] 아래 흐린 색상으로 표시되는 두 번째 줄. null이면 생략된다.
 * @param enabled [onClick]/[onLongClick]이 입력에 반응할지 여부. 둘 다 null이면 영향이 없다.
 * @param onClick 탭될 때 호출된다. null이고 [onLongClick]도 null이면 행은 전혀 클릭 가능하지 않다.
 * @param onLongClick 롱프레스 시 호출된다. non-null이면 [teddClickable]이 `combinedClickable`을
 * 사용하여, 일반 탭([onClick], 또는 [onClick]이 null이면 no-op)과 롱프레스를 모두 처리한다.
 * @param singleClick 이 행이 앱 전역 단일 클릭 가드에 참여할지 여부. 이 가드는 그 시간 창 안에
 * 도착한 두 번째 탭을 버린다. 행이 화면을 전환하거나 일회성 변경을 커밋하는 경우 켠다: 그렇지 않으면
 * 같은 프레임에 탭된 두 행이 각각 대상 화면을 push할 수 있는데, 이 가드는 바로 그 중복을 막기 위해
 * 존재한다. 선택을 토글하거나 제자리에서 확장되는 행에는 꺼 둔다. 이 가드는 앱 전체에서 공유되므로,
 * 충분히 가까운 시점에 뒤따르는 *다른* 행에 대한 의도적인 탭까지 삼켜 버리기 때문이다.
 * @param contentPadding 행 가장자리와 콘텐츠 사이의 패딩. null이면 테마의 medium/small 조합을
 * 사용한다.
 * @param showDivider 행 하단 가장자리를 따라 1dp 머리카락 굵기 선을 그릴지 여부.
 * @param leadingContent 제목/보조 텍스트 column 앞에 표시되는 콘텐츠로, 아이콘 등.
 * @param trailingContent 제목/보조 텍스트 column 뒤에 표시되는 콘텐츠로, 화살표 등.
 */
@Composable
fun TeddListItem(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    singleClick: Boolean = false,
    contentPadding: PaddingValues? = null,
    showDivider: Boolean = true,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.medium,
        vertical = spacing.small,
    )
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val dividerColor = colors.outlineVariant
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = spacing.rowHeight)
        .run {
            if (onClick != null || onLongClick != null) {
                teddClickable(
                    onClick = onClick ?: {},
                    enabled = enabled,
                    onLongClick = onLongClick,
                    singleClick = singleClick,
                )
            } else {
                this
            }
        }

    Row(
        modifier = if (showDivider) {
            rowModifier
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val y = size.height - strokeWidth / 2f
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }
                .padding(resolvedContentPadding)
        } else {
            rowModifier.padding(resolvedContentPadding)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        leadingContent?.invoke(this)
        Column(modifier = Modifier.weight(1f)) {
            TeddText(
                text = title,
                style = typography.settingTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null) {
                TeddText(
                    text = supportingText,
                    style = typography.settingDescription,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent?.invoke(this)
    }
}

/**
 * 읽기 전용 상세 화면(문서 정보, "정보" 패널)을 위한 라벨-위-값 쌍으로, 흐린 색상의 캡션을 실제 값
 * 위에 쌓아, 사실을 나열하는 행이 모든 호출자가 캡션 색상과 활자 스케일 조합을 직접 다시 고르지
 * 않고도 일관되게 읽히게 한다.
 *
 * @param label [value] 위에 흐린 색상으로 표시되는 캡션.
 * @param value [label] 아래 앱의 제목 스타일로 표시되는 값 텍스트.
 * @param modifier 행 루트에 적용되는 modifier.
 */
@Composable
fun TeddInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        TeddText(
            text = label,
            style = typography.documentMeta,
            color = teddReaderColors().onSurfaceVariant,
        )
        TeddText(
            text = value,
            style = typography.settingTitle,
        )
    }
}

/**
 * Material의 `TopAppBar` 대신 일반 [Row]/[TeddText]로 만든 화면 수준 앱 바. 이 앱의 화면들은
 * 내비게이션 슬롯, 남은 너비를 차지하는 한 줄짜리 제목, 후행 액션 행을 갖는 고정 높이 바만 필요할
 * 뿐, 이 앱의 화면들이 쓰지 않는 `TopAppBar`의 스크롤 축소 동작은 필요 없기 때문이다.
 * [LocalContentColor]를 바 전체에 대해 한 번 설정해 두어, [navigationIcon]과 [actions]가 각자 색상
 * 스킴을 직접 읽지 않고도 올바르게 tint된다.
 *
 * @param title [teddReaderTypography]의 `titleLarge` 스타일로 표시되며, [actions] 슬롯이
 * `weight(1f)`로 밀어붙여 한 줄로 잘리는 바의 제목.
 * @param modifier 바 루트에 적용되는 modifier.
 * @param navigationIcon 바의 시작 부분에 표시되는 콘텐츠로, 보통 뒤로 가기 [TeddIconButton]이다.
 * null이면 생략된다.
 * @param contentPadding 바 가장자리와 콘텐츠 사이의 패딩. null이면 테마의 medium/small 조합을
 * 사용한다.
 * @param actions 바의 끝 부분에 표시되는 콘텐츠로, 보통 하나 이상의 [TeddIconButton]이다.
 */
@Composable
fun TeddTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable RowScope.() -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
    contentPadding: PaddingValues? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val typography = teddReaderTypography()
    val spacing = teddReaderSpacing()
    val colors = teddReaderColors()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.medium,
        vertical = spacing.small,
    )

    CompositionLocalProvider(LocalContentColor provides teddReaderColors().onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.surface)
                .windowInsetsPadding(windowInsets)
                .heightIn(min = spacing.rowHeight)
                .padding(resolvedContentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            navigationIcon?.invoke(this)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
            ) {
                TeddText(
                    text = title,
                    style = typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    TeddText(
                        text = subtitle,
                        style = typography.documentMeta,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}
