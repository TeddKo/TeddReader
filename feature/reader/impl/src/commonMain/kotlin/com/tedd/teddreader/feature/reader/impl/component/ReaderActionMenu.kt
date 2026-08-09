package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.ui.component.TeddDropdownMenuItem
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.teddString
import com.tedd.teddreader.feature.reader.impl.ReaderMenuAction

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
            contentDescription = teddString("Reader actions", "리더 작업"),
        ) {
            Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            ReaderMenuSection(
                title = teddString("Navigation", "탐색"),
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
            HorizontalDivider()
            ReaderMenuSection(
                title = teddString("Appearance", "화면"),
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
            HorizontalDivider()
            ReaderMenuSection(
                title = teddString("Reading tools", "읽기 도구"),
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

@Composable
private fun ReaderMenuSection(
    title: String,
    actions: List<ReaderMenuAction>,
    isCurrentPageSaved: Boolean = false,
    onActionSelected: (ReaderMenuAction) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    headerPadding: PaddingValues = PaddingValues(
        start = DefaultTeddReaderSpacing.medium,
        top = DefaultTeddReaderSpacing.small,
        end = DefaultTeddReaderSpacing.medium,
        bottom = DefaultTeddReaderSpacing.xxSmall,
    ),
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            modifier = Modifier.padding(headerPadding),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun ReaderMenuAction.label(isCurrentPageSaved: Boolean): String = when (this) {
    ReaderMenuAction.Search -> teddString("Search in document", "문서에서 검색")
    ReaderMenuAction.ToggleSavedPlace -> if (isCurrentPageSaved) teddString("Remove saved place", "저장한 위치 제거") else teddString("Save current page", "현재 페이지 저장")
    ReaderMenuAction.SavedPlaces -> teddString("Saved places", "저장한 위치")
    ReaderMenuAction.TableOfContents -> teddString("Table of contents", "목차")
    ReaderMenuAction.GoToPage -> teddString("Jump to page", "페이지로 이동")
    ReaderMenuAction.ViewOptions -> teddString("Display", "표시")
    ReaderMenuAction.FontOptions -> teddString("Typography", "글자")
    ReaderMenuAction.ThemeOptions -> teddString("Theme", "테마")
    ReaderMenuAction.PageTurnOptions -> teddString("Page movement", "페이지 이동")
    ReaderMenuAction.AutoScrollOptions -> teddString("Auto-scroll", "자동 스크롤")
    ReaderMenuAction.BrightnessOptions -> teddString("Brightness", "밝기")
    ReaderMenuAction.ControlOptions -> teddString("Bottom bar", "하단 바")
    ReaderMenuAction.DocumentInfo -> teddString("Document details", "문서 정보")
}

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
