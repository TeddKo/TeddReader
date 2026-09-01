package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * 리더의 상단 바: 책에서 리더가 어디에 있는지, 그리고 그것에 작용하는 액션들.
 *
 * 한 줄이 아니라 두 줄의 텍스트 — 책 자체 제목 위의 조용한 라벨 — 인 이유는, 제목은 사용자가
 * 페이지를 알아보는 데 필요한 것이고 라벨은 그것이 어느 챕터에 속하는지 알려 주는 것이기 때문이다.
 * 둘 다 한 줄이며 말줄임표로 처리된다: 긴 챕터 제목이 액션들을 바 밖으로 밀어내서는 안 된다.
 *
 * @param title 읽고 있는 대상을 식별하는 텍스트. 리더 기능은 챕터 제목을 전달하며, 이는 챕터의
 * 첫 페이지뿐 아니라 모든 페이지에서 고정된 채로 유지된다.
 * @param style 바 자체의 색상을 결정하는 리더의 스타일([ReaderChromeSurface] 참고).
 * @param modifier 바에 적용된다; 부모의 너비를 채운다.
 * @param titleLabel [title] 위의 조용한 줄. null이거나 공백이면 여백을 남기지 않고 완전히 생략된다.
 * @param navigationIcon 선행 컨트롤로, 보통 뒤로 가기다. null이면 그 공간을 제목에 넘긴다.
 * @param windowInsets 바가 비워 두어야 하는 시스템 인셋 — 상단 바의 상태 표시줄.
 * @param contentPadding 그 인셋 안쪽, 바의 행 주위의 인셋. null이면 테마의 small/xxSmall 조합을
 * 사용한다.
 * @param actions 바의 끝에 행으로 배치되는 후행 컨트롤.
 * @param titleAtEnd true이면 같은 바를 오른쪽에서 왼쪽으로 배치하기 위해 두 텍스트 줄을 모두 시작이
 * 아닌 끝에 정렬한다.
 */
@Composable
fun ReaderTopControls(
    title: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    titleLabel: String? = "Reading",
    navigationIcon: (@Composable () -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    contentPadding: PaddingValues? = null,
    actions: @Composable RowScope.() -> Unit = {},
    titleAtEnd: Boolean = false,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.small,
        vertical = spacing.xxSmall,
    )
    val typography = teddReaderTypography()

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = resolvedContentPadding,
        windowInsets = windowInsets,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.none),
                horizontalAlignment = if (titleAtEnd) Alignment.End else Alignment.Start,
            ) {
                if (!titleLabel.isNullOrBlank()) {
                    TeddText(
                        text = titleLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (titleAtEnd) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        style = typography.readerCaption,
                    )
                }
                TeddText(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (titleAtEnd) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    style = typography.titleLarge,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}

/**
 * 리더의 하단 바: 진행률, 그리고 책을 통해 이동하는 액션들.
 *
 * 머리카락 굵기 선은 *위쪽* 가장자리(`dividerAtTop`)에 있으며, 이는 아래 기기 자체의 가장자리가
 * 아니라 위쪽 페이지와 이 바를 구분하는 것이다.
 *
 * @param style 바의 색상을 결정하는 리더의 스타일.
 * @param modifier 바에 적용된다; 부모의 너비를 채운다.
 * @param progress 바 자체의 box scope 안에 그려질, 스스로 크기를 정할 수 있는 진행률 인디케이터.
 * 액션만 보여주는 바에는 null.
 * @param windowInsets 바가 비워 두어야 하는 시스템 인셋 — 하단 바의 내비게이션 바.
 * @param contentPadding 그 인셋 안쪽, 바의 콘텐츠 주위의 인셋. null이면 테마의 small/xxSmall 조합을
 * 사용한다.
 * @param actions 행으로 배치되는 바의 컨트롤들.
 */
@Composable
fun ReaderBottomControls(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    progress: (@Composable BoxScope.() -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    contentPadding: PaddingValues? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.small,
        vertical = spacing.xxSmall,
    )

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = resolvedContentPadding,
        windowInsets = windowInsets,
        dividerAtTop = true,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < breakpoints.compact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    if (progress != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            progress.invoke(this)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            spacing.xxSmall,
                            Alignment.End,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions()
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    if (progress != null) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            progress.invoke(this)
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions()
                    }
                }
            }
        }
    }
}

/**
 * 페이지를 감싼 두 바 — 프리뷰할 가치가 있는 유일한 배치다. 각 바의 색상과 머리카락 굵기 선은 그
 * 사이의 종이를 배경으로 해야만 의미가 있다.
 *
 * @param style 바와 페이지를 그릴 리더 스타일.
 * @param darkTheme 주변 앱 테마가 다크인지 여부. 바는 이를 따르지 않는다 — 바는 [style]을 따른다 —
 * 그래서 이 프리뷰는 그 독립성을 가정하는 대신 실제로 보여준다.
 */
@Composable
private fun ReaderControlsPreviewContent(style: ReaderStyle, darkTheme: Boolean = false) {
    val spacing = teddReaderSpacing()

    TeddReaderTheme(darkTheme = darkTheme) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            ReaderTopControls(
                title = "A Very Long Chapter Title That Must Ellipsize Cleanly",
                style = style,
                navigationIcon = {
                    TeddIconButton(onClick = {}, contentDescription = "Back") {
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Toggle bookmark") {
                        TeddIcon(imageVector = TeddIcons.BookmarkOutline, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "More options") {
                        TeddIcon(imageVector = TeddIcons.MoreVert, contentDescription = null)
                    }
                },
            )

            ReaderBottomControls(
                style = style,
                progress = {
                    ReaderProgressBar(
                        pageIndex = PageIndex(current = 4, total = 240),
                        showPageLabel = true,
                        showPercentLabel = false,
                        compact = true,
                    )
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Previous page") {
                        TeddIcon(imageVector = TeddIcons.Previous, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Next page") {
                        TeddIcon(imageVector = TeddIcons.Next, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Enable auto-scroll") {
                        TeddIcon(imageVector = TeddIcons.Play, contentDescription = null)
                    }
                },
            )
        }
    }
}

/** 낮 종이 위, 일반 너비의 두 바. */
@Preview(widthDp = 360)
@Composable
private fun ReaderControlsPreview() {
    ReaderControlsPreviewContent(style = ReaderStyle())
}

/** 너비 280dp로, 긴 제목이 액션들을 밀어내는 대신 말줄임표로 처리되어야 하는 경우. */
@Preview(widthDp = 280)
@Composable
private fun ReaderControlsNarrowPreview() {
    ReaderControlsPreviewContent(style = ReaderStyle())
}

/** 다크 앱 테마의 밤 종이로, 머리카락 굵기 선과 캡션이 가장 보이기 어려운 경우. */
@Preview(widthDp = 360)
@Composable
private fun ReaderControlsDarkPreview() {
    ReaderControlsPreviewContent(style = darkReaderStyle(), darkTheme = true)
}
