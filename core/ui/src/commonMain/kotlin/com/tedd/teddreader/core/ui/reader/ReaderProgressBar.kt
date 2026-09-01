package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.extension.toOneBasedPageNumber
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddText

/**
 * 책에서 사용자가 얼마나 진행했는지: 바 하나, 선택적으로 페이지 수와 퍼센트를 함께 보여준다.
 *
 * 수치는 [PageIndex]에서 오는데, 그 total은 책 전체가 아니라 "지금까지 알려진 페이지 수"다 — 그래서
 * 점진적 임포트가 아직 진행 중인 동안 이 바는 지금까지 측정된 범위에 대한 진행률을 정직하게 보여주고,
 * 바와 라벨 모두 total이 늘어남에 따라 함께 움직인다.
 *
 * @param pageIndex 현재 페이지와 지금까지 알려진 total.
 * @param modifier 바에 적용된다.
 * @param showPageLabel 바 옆에 "current / total"을 표시할지 여부.
 * @param showPercentLabel 퍼센트를 표시할지 여부.
 * @param compact true이면 하단 바를 위해 라벨, 바, 퍼센트를 한 행에 배치한다; false이면 시트나 넓은
 * 레이아웃이 사용하는 방식대로 쌓는다.
 */
@Composable
fun ReaderProgressBar(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
    showPageLabel: Boolean = false,
    showPercentLabel: Boolean = false,
    compact: Boolean = false,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    if (compact) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
        ) {
            if (showPageLabel) {
                ReaderPageLabel(
                    pageIndex = pageIndex,
                    modifier = Modifier,
                )
            }

            LinearProgressIndicator(
                progress = { pageIndex.progress },
                modifier = Modifier.weight(1f),
            )

            if (showPercentLabel) {
                TeddText(
                    text = pageIndex.percentLabel(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.readerCaption,
                )
            }
        }
        return
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        if (showPageLabel) {
            ReaderPageLabel(pageIndex = pageIndex)
        }

        LinearProgressIndicator(
            progress = { pageIndex.progress },
            modifier = Modifier.fillMaxWidth(),
        )

        if (showPercentLabel) {
            TeddText(
                text = pageIndex.percentLabel(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = typography.readerCaption,
            )
        }
    }
}

/**
 * 진행률 트랙 없이 숫자만 보여주는 바를 위한, 페이지 카운터 단독.
 *
 * @param pageIndex 현재 페이지와 지금까지 알려진 total.
 * @param modifier 라벨에 적용된다.
 */
@Composable
fun ReaderPageLabel(
    pageIndex: PageIndex,
    modifier: Modifier = Modifier,
) {
    val typography = teddReaderTypography()
    val current = if (pageIndex.total == 0) 0 else pageIndex.current.toOneBasedPageNumber()

    TeddText(
        text = pageIndex.pageLabel(current),
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = typography.readerCaption,
    )
}


/**
 * @receiver 렌더링할 page index.
 * @param current 표시할, 이미 1부터 시작하는 페이지 번호. total이 0이면 1이 아니라 0을 표시하여,
 * 아직 아무것도 측정되지 않은 책이 첫 페이지에 있다고 주장하지 않게 한다.
 * @return `"current / total"` 형식의 텍스트.
 */
private fun PageIndex.pageLabel(current: Int = if (total == 0) 0 else this.current.toOneBasedPageNumber()): String = "$current / $total"

/**
 * @receiver 렌더링할 page index.
 * @return 정수 퍼센트 문자열로 표현된 진행률. 마지막 페이지 전에 100%를 표시하지 않도록 반올림이
 * 아니라 버림 처리된다.
 */
private fun PageIndex.percentLabel(): String = "${(progress * 100).toInt()}%"

/** 두 라벨을 모두 보여주는, 쌓인 레이아웃. */
@Preview(widthDp = 360)
@Composable
private fun ReaderProgressPreview() {
    TeddReaderTheme {
        Column(verticalArrangement = Arrangement.spacedBy(teddReaderSpacing().small)) {
            val pageIndex = PageIndex(current = 4, total = 20)
            ReaderProgressBar(
                pageIndex = pageIndex,
                showPageLabel = true,
                showPercentLabel = true,
            )
            ReaderPageLabel(pageIndex = pageIndex)
        }
    }
}

/** 리더의 하단 바가 보여주는 대로, 밤 종이 위의 한 줄 레이아웃. */
@Preview(widthDp = 360)
@Composable
private fun ReaderProgressCompactDarkPreview() {
    TeddReaderTheme(darkTheme = true) {
        ReaderProgressBar(
            pageIndex = PageIndex(current = 88, total = 240),
            showPageLabel = true,
            showPercentLabel = false,
            compact = true,
        )
    }
}
