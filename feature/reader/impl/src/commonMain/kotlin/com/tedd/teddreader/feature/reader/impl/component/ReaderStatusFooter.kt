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
 * The reader's bottom status bar: battery percentage on the left, the document title centered, and
 * read progress on the right, shown as part of the reader's own chrome.
 *
 * @param title the document title to show, centered and truncated to one line.
 * @param readProgressPercent how far into the document the reader currently is, 0 to 100; clamped
 *   defensively before display, or null while the document's total is still incomplete.
 * @param batteryPercent the device's battery charge, 0 to 100, or null when the platform could not
 *   report one — shown as `"--%"` in that case.
 * @param style the reading style, used for [ReaderChromeSurface]'s theming.
 * @param modifier applied to the outer [ReaderChromeSurface].
 * @param windowInsets insets to pad the bar by, e.g. so it clears a bottom system bar.
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

/** Preview: [ReaderStatusFooter] with a long title, a mid battery level, and partial progress. */
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
