package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddDivider
import com.tedd.teddreader.core.ui.component.TeddDropdownMenu
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.feature.reader.impl.ReaderMenuAction
import org.jetbrains.compose.resources.stringResource

/**
 * 리더 상단 바의 overflow 메뉴: 탭하면 내비게이션, 외관, 읽기 도구 섹션으로 그룹화된 드롭다운을 여는
 * "더보기" 아이콘 버튼이며, 각 섹션은 [ReaderMenuSection]이 그린다. 모든 항목은 자신이 나타내는
 * [ReaderMenuAction]을 [onActionSelected]로 전달하고 스스로 닫힌다; 이 composable은 선택지를 보여줄
 * 뿐이며 행동이 무엇을 의미하는지는 해석하지 않는다.
 *
 * @param expanded 드롭다운이 현재 열려 있는지 여부.
 * @param isCurrentPageSaved 현재 페이지에 이미 저장된 위치가 있는지 여부로, 토글 액션의 "저장"/"저장된
 *   위치 삭제" 문구를 고르는 데 쓰인다.
 * @param onExpandedChange 메뉴가 열리거나 닫혀야 할 때 호출된다.
 * @param onActionSelected 메뉴가 닫힌 뒤, 사용자가 고른 액션과 함께 호출된다.
 * @param modifier 메뉴의 앵커 [Box]에 적용된다.
 */
@Composable
fun ReaderActionMenu(
    expanded: Boolean,
    isCurrentPageSaved: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onActionSelected: (ReaderMenuAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        TeddIconButton(
            onClick = { onExpandedChange(true) },
            contentDescription = stringResource(Res.string.reader_actions),
        ) {
            TeddIcon(imageVector = TeddIcons.MoreVert, contentDescription = null)
        }
        TeddDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            ReaderMenuSection(
                title = stringResource(Res.string.navigation),
                actions = listOf(
                    ReaderMenuAction.TableOfContents,
                    ReaderMenuAction.GoToPage,
                    ReaderMenuAction.SavedPlaces,
                    ReaderMenuAction.Search,
                    ReaderMenuAction.DocumentInfo,
                ),
                isCurrentPageSaved = isCurrentPageSaved,
                onActionSelected = onActionSelected,
                onDismiss = { onExpandedChange(false) },
            )
            TeddDivider()
            ReaderMenuSection(
                title = stringResource(Res.string.appearance),
                actions = listOf(
                    ReaderMenuAction.ViewOptions,
                    ReaderMenuAction.FontOptions,
                    ReaderMenuAction.ThemeOptions,
                    ReaderMenuAction.BrightnessOptions,
                    ReaderMenuAction.ControlOptions,
                ),
                onActionSelected = onActionSelected,
                onDismiss = { onExpandedChange(false) },
            )
            TeddDivider()
            ReaderMenuSection(
                title = stringResource(Res.string.reading_tools),
                actions = listOf(
                    ReaderMenuAction.ToggleSavedPlace,
                    ReaderMenuAction.PageTurnOptions,
                    ReaderMenuAction.AutoScrollOptions,
                ),
                isCurrentPageSaved = isCurrentPageSaved,
                onActionSelected = onActionSelected,
                onDismiss = { onExpandedChange(false) },
            )
        }
    }
}

/**
 * [ReaderActionMenu] 드롭다운 안의, 제목이 달린 메뉴 항목 그룹 하나: 섹션 헤더 뒤로 [actions]의
 * [ReaderMenuAction]마다 한 행씩 이어지며, 각 행은 [label]을 통해 라벨이 붙는다.
 *
 * @param title 섹션의 헤더 텍스트.
 * @param actions 표시 순서대로 나열할 액션들.
 * @param isCurrentPageSaved 문구가 이 값에 좌우되는 액션을 위해 [label]로 전달된다; 그렇지 않은
 *   액션에는 쓰이지 않는다.
 * @param onActionSelected 사용자가 탭한 액션과 함께 호출된다.
 * @param onDismiss 부모 드롭다운을 닫기 위해 [onActionSelected]와 함께 호출된다.
 * @param modifier 이 섹션의 column에 적용된다.
 * @param headerPadding 섹션 헤더 텍스트 주위의 padding; null이면 테마의 medium/small/xxSmall
 *   간격으로 해석된다.
 */
@Composable
private fun ReaderMenuSection(
    title: String,
    actions: List<ReaderMenuAction>,
    isCurrentPageSaved: Boolean = false,
    onActionSelected: (ReaderMenuAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedHeaderPadding = headerPadding ?: PaddingValues(
        start = spacing.medium,
        top = spacing.small,
        end = spacing.medium,
        bottom = spacing.xxSmall,
    )

    Column(modifier = modifier) {
        TeddText(
            text = title,
            modifier = Modifier.padding(resolvedHeaderPadding),
            style = teddReaderTypography().labelSmall,
            color = teddReaderColors().onSurfaceVariant,
        )
        actions.forEach { action ->
            TeddDropdownMenuItem(
                text = action.label(isCurrentPageSaved),
                onClick = {
                    onDismiss()
                    onActionSelected(action)
                },
            )
        }
    }
}

/**
 * 리더 액션 메뉴에서 이 액션에 대해 표시할 라벨.
 *
 * @receiver 라벨을 붙일 액션.
 * @param isCurrentPageSaved 현재 페이지에 이미 저장된 위치가 있는지 여부; "저장" 대 "삭제" 문구를
 *   고르는 데는 [ReaderMenuAction.ToggleSavedPlace]만 이 값을 사용한다.
 * @return 이 액션에 대해 표시되는 지역화된 라벨.
 */
@Composable
private fun ReaderMenuAction.label(isCurrentPageSaved: Boolean): String = when (this) {
    ReaderMenuAction.Search -> stringResource(Res.string.search_in_document)
    ReaderMenuAction.ToggleSavedPlace -> if (isCurrentPageSaved) stringResource(Res.string.remove_saved_place) else stringResource(Res.string.save_current_page)
    ReaderMenuAction.SavedPlaces -> stringResource(Res.string.saved_places)
    ReaderMenuAction.TableOfContents -> stringResource(Res.string.table_of_contents)
    ReaderMenuAction.GoToPage -> stringResource(Res.string.jump_to_page)
    ReaderMenuAction.ViewOptions -> stringResource(Res.string.display)
    ReaderMenuAction.FontOptions -> stringResource(Res.string.typography)
    ReaderMenuAction.ThemeOptions -> stringResource(Res.string.theme)
    ReaderMenuAction.PageTurnOptions -> stringResource(Res.string.page_movement)
    ReaderMenuAction.AutoScrollOptions -> stringResource(Res.string.auto_scroll)
    ReaderMenuAction.BrightnessOptions -> stringResource(Res.string.reader_option_brightness)
    ReaderMenuAction.ControlOptions -> stringResource(Res.string.bottom_bar)
    ReaderMenuAction.DocumentInfo -> stringResource(Res.string.document_details)
}

/** IDE 미리보기 패널을 위한, 기본값인 닫힌 상태의 [ReaderActionMenu] Compose 미리보기. */
@Preview
@Composable
private fun ReaderActionMenuPreview() {
    TeddReaderTheme {
        ReaderActionMenu(
            expanded = false,
            isCurrentPageSaved = false,
            onExpandedChange = {},
            onActionSelected = {},
        )
    }
}
