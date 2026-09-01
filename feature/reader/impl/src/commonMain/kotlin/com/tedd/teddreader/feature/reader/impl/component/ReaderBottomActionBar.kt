package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderMotion
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddSlider
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.*
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.reader.ReaderBottomControls
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

internal fun readerChapterPageLabel(chapterTitle: String?, chapterPageIndex: PageIndex?): String? =
    if (chapterTitle.isNullOrBlank() || chapterPageIndex == null || chapterPageIndex.total <= 0) {
        null
    } else {
        "$chapterTitle • ${chapterPageIndex.current + 1}/${chapterPageIndex.total}"
    }

/**
 * 리더의 하단 chrome: 페이지 위치 슬라이더/라벨과 이전/다음/자동 스크롤 컨트롤로, 리더의 나머지
 * 컨트롤들과 함께 페이드 인/아웃된다.
 *
 * 슬라이더는 자신만의 상태를 갖는 대신 [sliderValue]로 구동된다, 그래야 진행 중인 드래그와
 * [onPageSelected]가 커밋한 페이지 인덱스가 서로 다투지 않고 어긋날 수 있다 — 호출자는 실시간 드래그
 * 값(예: `ReaderScreen`의 `bottomSliderValue`)을 보유하고 있다가 제스처가 끝나야만 [onPageSelected]를
 * 호출할 것으로 기대된다. 내부적으로 [latestSelectedPage]는 반올림된 목표 페이지를 그대로 반영하며,
 * 슬라이더 자체는 연속적인 [Float] 값만 가지고 있으므로 드래그가 [onPageSelected]를 통해 되돌려 보고하는
 * 값이 바로 이것이다.
 *
 * @param pageIndex 슬라이더의 범위와 라벨이 만들어지는 기준이 되는 현재 페이지와 전체 페이지 수.
 * @param style [ReaderBottomControls]에 테마 설정을 위해 넘겨지는 활성 리더 style.
 * @param isAutoScrollEnabled 자동 스크롤이 실행 중인 동안 true; 수동 페이지 변경이 자동 변경과 경쟁하지
 *   않도록 슬라이더와 이전/다음 버튼을 비활성화하고, 자동 스크롤 버튼의 아이콘/라벨을 "일시정지"로
 *   바꾼다.
 * @param showProgress 페이지 위치 행(라벨 + 슬라이더)을 아예 보여줄지 여부; 이전/다음/자동 스크롤
 *   버튼은 이와 무관하게 항상 보인다.
 * @param isPaginationComplete 책이 아직 백그라운드에서 import되고 있는 동안에만 false다
 *   (`ReaderUiState.isPaginationComplete` 참고). 이때 슬라이더는 숨겨지는 대신 보이는 채로 비활성화된
 *   상태를 유지한다 — 전체 수가 늘어나면서 엄지 손잡이가 저절로 미끄러지는 것은 기다리는 것보다 더
 *   나쁘다 — 그리고 페이지 라벨은 고정된 잘못된 총합을 보여주는 대신 끝에 "+"를 붙인 채로 계속 늘어난다.
 * @param onAutoScrollToggle 자동 스크롤 재생/일시정지 버튼을 탭했을 때 호출된다.
 * @param onPreviousPage 이전 페이지 버튼을 탭했을 때 호출된다.
 * @param onNextPage 다음 페이지 버튼을 탭했을 때 호출된다.
 * @param onPageSelected 슬라이더 드래그가 끝나면 반올림된 목표 페이지와 함께 호출된다.
 * @param sliderValue 호출자가 소유하는, 슬라이더의 현재(드래그 도중일 수 있는) 값.
 * @param onSliderValueChange 드래그가 끝나기 전, 슬라이더가 드래그되는 동안 계속 호출된다.
 * @param modifier 하단 바 전체에 적용되는 modifier.
 * @param chapterTitle 로컬 페이지 분수 앞에 보여줄 EPUB 챕터 제목; null이면 문서 전체 기준 페이지
 *   라벨을 유지한다.
 * @param chapterPageIndex [chapterTitle] 안에서의 0-기반 위치와 전체 수; null이거나 전체 수가 비어
 *   있으면 문서 전체 기준 페이지 라벨을 유지한다.
 * @param windowInsets 바가 시스템 chrome(예: 내비게이션 바)을 피하도록 남겨두는 인셋.
 * @param canGoPrevious 이전 페이지 버튼이 활성화되어 있는지 여부; 기본값은 "첫 페이지가 아님"이다.
 * @param canGoNext 다음 페이지 버튼이 활성화되어 있는지 여부; 기본값은 "알려진 마지막 페이지가 아님"이다.
 *
 * `latestSelectedPage`는 반올림된 목표 페이지를 그대로 반영하므로, 슬라이더 자체는 언제나 연속적인
 * `Float`만 만들어내지만 `onPageSelected`는 `Int`를 보고한다. 이는 무조건이 아니라 마지막 페이지를
 * 기준으로 remember되므로, 페이지 수를 바꾸는 재 페이지 나누기가 일어나면 슬라이더의 범위와 함께
 * 초기화되어 오래된 페이지 번호를 보고하지 않는다.
 */
@Composable
fun ReaderBottomActionBar(
    pageIndex: PageIndex,
    style: ReaderStyle,
    isAutoScrollEnabled: Boolean,
    showProgress: Boolean,
    isPaginationComplete: Boolean = true,
    onAutoScrollToggle: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPageSelected: (Int) -> Unit,
    sliderValue: Float,
    onSliderValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    chapterTitle: String? = null,
    chapterPageIndex: PageIndex? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    canGoPrevious: Boolean = pageIndex.current > 0,
    canGoNext: Boolean = pageIndex.current < (pageIndex.total - 1).coerceAtLeast(0),
) {
    val spacing = teddReaderSpacing()
    val motion = teddReaderMotion()
    val typography = teddReaderTypography()
    val lastPage = (pageIndex.total - 1).coerceAtLeast(0)
    val sliderRange = if (lastPage > 0) 0f..lastPage.toFloat() else 0f..1f
    val displayedSliderValue = sliderValue.coerceIn(sliderRange.start, sliderRange.endInclusive)
    val selectedPage = displayedSliderValue.roundToInt().coerceIn(0, lastPage)
    var latestSelectedPage by remember(lastPage) { mutableIntStateOf(selectedPage) }

    LaunchedEffect(selectedPage) {
        latestSelectedPage = selectedPage
    }

    val documentPageLabel = if (pageIndex.total == 0) {
        stringResource(Res.string.page_fraction_zero)
    } else if (isPaginationComplete) {
        "${latestSelectedPage + 1} / ${pageIndex.total}"
    } else {
        "${latestSelectedPage + 1} / ${pageIndex.total}+"
    }
    val chapterPageLabel = readerChapterPageLabel(chapterTitle, chapterPageIndex)
    val pageLabel = chapterPageLabel ?: documentPageLabel

    AnimatedContent(
        modifier = modifier,
        targetState = showProgress,
        transitionSpec = {
            fadeIn(tween(motion.mediumDurationMs)) togetherWith
                fadeOut(tween(motion.shortDurationMs))
        },
        label = "Reader progress visibility",
    ) { progressVisible ->
        ReaderBottomControls(
            style = style,
            windowInsets = windowInsets,
            progress = if (progressVisible) {
                {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                    ) {
                        TeddText(
                            text = pageLabel,
                            modifier = if (chapterPageLabel != null) Modifier.weight(1f) else Modifier,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = typography.readerCaption,
                        )
                        TeddSlider(
                            value = displayedSliderValue,
                            onValueChange = { value ->
                                val boundedValue = value.coerceIn(sliderRange.start, sliderRange.endInclusive)
                                latestSelectedPage = boundedValue.roundToInt().coerceIn(0, lastPage)
                                onSliderValueChange(boundedValue)
                            },
                            onValueChangeFinished = { onPageSelected(latestSelectedPage) },
                            valueRange = sliderRange,
                            enabled = pageIndex.total > 1 && !isAutoScrollEnabled && isPaginationComplete,
                            modifier = Modifier.weight(if (chapterPageLabel != null) 2f else 1f),
                        )
                    }
                }
            } else {
                null
            },
            actions = {
                TeddIconButton(
                    onClick = onPreviousPage,
                    enabled = canGoPrevious && !isAutoScrollEnabled,
                    contentDescription = stringResource(Res.string.previous_page),
                ) {
                    TeddIcon(imageVector = TeddIcons.Previous, contentDescription = null)
                }
                TeddIconButton(
                    onClick = onNextPage,
                    enabled = canGoNext && !isAutoScrollEnabled,
                    contentDescription = stringResource(Res.string.next_page),
                ) {
                    TeddIcon(imageVector = TeddIcons.Next, contentDescription = null)
                }
                TeddIconButton(
                    onClick = onAutoScrollToggle,
                    contentDescription = if (isAutoScrollEnabled) stringResource(Res.string.pause_auto_scroll) else stringResource(Res.string.start_auto_scroll),
                ) {
                    TeddIcon(
                        imageVector = if (isAutoScrollEnabled) TeddIcons.Pause else TeddIcons.Play,
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

/** 책 중간 지점에서, 진행률이 보이고 자동 스크롤이 꺼진 [ReaderBottomActionBar]의 Compose 미리보기. */
@Preview(widthDp = 360)
@Composable
private fun ReaderBottomActionBarPreview() {
    TeddReaderTheme {
        ReaderBottomActionBar(
            pageIndex = PageIndex(current = 4, total = 20),
            style = ReaderStyle(),
            isAutoScrollEnabled = false,
            showProgress = true,
            onAutoScrollToggle = {},
            onPreviousPage = {},
            onNextPage = {},
            onPageSelected = {},
            sliderValue = 4f,
            onSliderValueChange = {},
        )
    }
}
