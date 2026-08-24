package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * A tappable, two-line list row with an optional divider — the shared building block for settings
 * screens, document lists, and menus that need a title, an optional supporting line, and optional
 * leading/trailing content, all wrapped to the same 56dp minimum row height, content padding, and
 * bottom-hairline treatment. Exists so those screens do not each hand-assemble a [Row] plus a manual
 * [drawBehind] divider and re-derive the same click/long-click branching every time a row needs a
 * long-press action (e.g. a context menu) in addition to a tap.
 *
 * @param title The row's primary text, shown in [teddReaderTypography]'s `settingTitle` style,
 * truncated to 2 lines.
 * @param modifier Modifier applied to the row's root.
 * @param supportingText A second line shown under [title] in a muted color; omitted when null.
 * @param enabled Whether [onClick]/[onLongClick] respond to input; has no effect when both are null.
 * @param onClick Invoked on tap; when null and [onLongClick] is also null, the row is not clickable
 * at all.
 * @param onLongClick Invoked on long-press; when non-null, the row uses `combinedClickable` so a
 * plain tap ([onClick], or a no-op if [onClick] is null) and a long-press are both handled.
 * @param contentPadding Padding between the row's edge and its content.
 * @param showDivider Whether a 1dp hairline is drawn along the row's bottom edge.
 * @param leadingContent Content shown before the title/supporting-text column, such as an icon.
 * @param trailingContent Content shown after the title/supporting-text column, such as a chevron.
 */
@Composable
fun TeddListItem(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.medium,
        vertical = DefaultTeddReaderSpacing.small,
    ),
    showDivider: Boolean = true,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .run {
            if (onLongClick != null) {
                combinedClickable(
                    enabled = enabled,
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                )
            } else if (onClick != null) {
                clickable(enabled = enabled, onClick = onClick)
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
                .padding(contentPadding)
        } else {
            rowModifier.padding(contentPadding)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        leadingContent?.invoke(this)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.settingTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = typography.settingDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent?.invoke(this)
    }
}

/**
 * A label-over-value pair for read-only detail screens (document info, "about" panels), stacking a
 * muted caption above the actual value so a row of facts reads consistently without every caller
 * re-picking the caption color and type-scale pairing by hand.
 *
 * @param label The caption shown above [value], in a muted color.
 * @param value The value text shown under [label], in the app's title style.
 * @param modifier Modifier applied to the row's root.
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
        Text(
            text = label,
            style = typography.documentMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = typography.settingTitle,
        )
    }
}

/**
 * A screen-level app bar built from plain [Row]/[Text] instead of Material's `TopAppBar`, because the
 * app's screens only ever need a fixed-height bar with a navigation slot, a single-line title that
 * takes the remaining width, and a trailing actions row — none of `TopAppBar`'s scroll-collapse
 * behavior, which this app's screens do not use. [LocalContentColor] is set once for the whole bar so
 * [navigationIcon] and [actions] tint correctly without each one reading the color scheme itself.
 *
 * @param title The bar's title, shown in [teddReaderTypography]'s `titleLarge` style, truncated to
 * one line with the [actions] slot squeezing it via `weight(1f)`.
 * @param modifier Modifier applied to the bar's root.
 * @param navigationIcon Content shown at the bar's start, typically a back [TeddIconButton]; omitted
 * when null.
 * @param contentPadding Padding between the bar's edge and its content.
 * @param actions Content shown at the bar's end, typically one or more [TeddIconButton]s.
 */
@Composable
fun TeddTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable RowScope.() -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.medium,
        vertical = DefaultTeddReaderSpacing.small,
    ),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val typography = teddReaderTypography()
    val spacing = teddReaderSpacing()

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(windowInsets)
                .heightIn(min = 56.dp)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            navigationIcon?.invoke(this)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
            ) {
                Text(
                    text = title,
                    style = typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = typography.documentMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
        }
    }
}
