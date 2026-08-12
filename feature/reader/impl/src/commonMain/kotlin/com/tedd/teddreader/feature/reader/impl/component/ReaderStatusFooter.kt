package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.reader_battery_percentage
import com.tedd.teddreader.core.ui.generated.resources.reader_battery_unavailable
import com.tedd.teddreader.core.ui.generated.resources.reader_read_progress_percentage
import com.tedd.teddreader.core.ui.icon.TeddIcons
import com.tedd.teddreader.core.ui.reader.ReaderChromeSurface
import org.jetbrains.compose.resources.stringResource

@Composable
fun ReaderStatusFooter(
    title: String,
    readProgressPercent: Int,
    batteryPercent: Int?,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
) {
    val typography = teddReaderTypography()
    val spacing = teddReaderSpacing()
    val boundedBatteryPercent = batteryPercent?.coerceIn(0, 100)
    val boundedReadProgressPercent = readProgressPercent.coerceIn(0, 100)
    val batteryDescription = boundedBatteryPercent?.let {
        stringResource(Res.string.reader_battery_percentage, it)
    } ?: stringResource(Res.string.reader_battery_unavailable)
    val progressDescription = stringResource(
        Res.string.reader_read_progress_percentage,
        boundedReadProgressPercent,
    )

    ReaderChromeSurface(
        style = style,
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = DefaultTeddReaderSpacing.medium,
            vertical = DefaultTeddReaderSpacing.xxSmall,
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
                Icon(
                    imageVector = TeddIcons.Battery,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = boundedBatteryPercent?.let { "$it%" } ?: "--%",
                    maxLines = 1,
                    style = typography.readerCaption,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = typography.readerCaption,
            )
            Text(
                text = "$boundedReadProgressPercent%",
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
