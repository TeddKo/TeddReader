package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderIconography
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.reader_battery_percentage
import com.tedd.teddreader.core.ui.generated.resources.reader_battery_unavailable
import com.tedd.teddreader.core.ui.generated.resources.reader_read_progress_percentage
import com.tedd.teddreader.core.ui.generated.resources.reader_read_progress_unavailable
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.reader.ReaderChromeSurface
import org.jetbrains.compose.resources.stringResource

/**
 * 리더 하단 상태 바: 왼쪽에 배터리 퍼센트, 가운데에 문서 제목, 오른쪽에 읽기 진행률을 보여주며, 리더
 * 자체 chrome의 일부로 표시된다.
 *
 * @param title 가운데 정렬되어 한 줄로 잘려 보이는 문서 제목.
 * @param readProgressPercent 리더가 현재 문서에서 얼마나 진행했는지, 0에서 100까지; 표시 전에
 *   방어적으로 clamp되며, 문서 전체 분량이 아직 미확정이면 null.
 * @param batteryPercent 기기의 배터리 잔량, 0에서 100까지, 플랫폼이 값을 보고할 수 없으면 null —
 *   그 경우 `"--%"`로 표시된다.
 * @param style [ReaderChromeSurface]의 테마 설정에 쓰이는 읽기 style.
 * @param modifier 바깥쪽 [ReaderChromeSurface]에 적용된다.
 * @param windowInsets 바에 패딩으로 줄 인셋으로, 예를 들어 하단 시스템 바를 피하게 해준다.
 */
@Composable
fun ReaderStatusFooter(
    title: String,
    readProgressPercent: Int?,
    batteryPercent: Int?,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
) {
    val typography = teddReaderTypography()
    val spacing = teddReaderSpacing()
    val boundedBatteryPercent = batteryPercent?.coerceIn(0, 100)
    val boundedReadProgressPercent = readProgressPercent?.coerceIn(0, 100)
    val batteryDescription = boundedBatteryPercent?.let {
        stringResource(Res.string.reader_battery_percentage, it)
    } ?: stringResource(Res.string.reader_battery_unavailable)
    val progressDescription = boundedReadProgressPercent?.let {
        stringResource(Res.string.reader_read_progress_percentage, it)
    } ?: stringResource(Res.string.reader_read_progress_unavailable)

    ReaderChromeSurface(
        style = style,
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = spacing.medium,
            vertical = spacing.xxSmall,
        ),
        windowInsets = windowInsets,
        dividerAtTop = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = batteryDescription },
                horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TeddIcon(
                    imageVector = TeddIcons.Battery,
                    contentDescription = null,
                    size = teddReaderIconography().extraSmall,
                )
                TeddText(
                    text = boundedBatteryPercent?.let { "$it%" } ?: "--%",
                    maxLines = 1,
                    style = typography.readerCaption,
                )
            }
            TeddText(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = typography.readerCaption,
            )
            TeddText(
                text = boundedReadProgressPercent?.let { "$it%" } ?: "--%",
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { contentDescription = progressDescription },
                maxLines = 1,
                textAlign = TextAlign.End,
                style = typography.readerCaption,
            )
        }
    }
}

/** 미리보기: 긴 제목, 중간 배터리 레벨, 일부 진행 상태의 [ReaderStatusFooter]. */
@Preview(widthDp = 280)
@Composable
private fun ReaderStatusFooterPreview() {
    TeddReaderTheme {
        ReaderStatusFooter(
            title = "A Very Long Reader Title",
            readProgressPercent = 57,
            batteryPercent = 73,
            style = ReaderStyle(),
        )
    }
}
