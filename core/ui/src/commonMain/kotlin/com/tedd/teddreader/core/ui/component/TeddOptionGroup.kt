package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * A titled section that groups related settings rows (typically [TeddRadioRow]/[TeddSwitchRow]/
 * [TeddCheckboxRow]/[TeddSliderRow]) under an optional header, giving every settings screen the same
 * header typography, header-to-content spacing, and surface background without each screen
 * re-deriving that layout by hand.
 *
 * @param title The section's header text, shown in [teddReaderTypography]'s `titleMedium` style;
 * when null and [description] is also null, no header is rendered at all.
 * @param modifier Modifier applied to the group's root.
 * @param description A second header line shown under [title] in a muted color; when non-null it
 * renders even if [title] is null.
 * @param isSelectableGroup Whether the content column carries `Modifier.selectableGroup()`, which is
 * what lets a screen reader announce each child's position within the set, such as "2 of 3", instead
 * of just that child's own state. Must be true when [content] is a run of [TeddRadioRow]s, because a
 * radio group is exactly the mutually exclusive set that announcement describes; must stay false when
 * [content] holds independent toggles such as [TeddSwitchRow] or [TeddCheckboxRow], since those rows do
 * not exclude one another and grouping them would announce a set relationship that is not true.
 * Defaults to false because most option groups in this app hold independent toggles, and a caller that
 * forgets to opt a radio group in produces a silent accessibility regression rather than a build
 * failure — the default that can never announce a false relationship is the safer one.
 * @param headerPadding Padding around the header block (only relevant when [title] or [description]
 * is non-null); null means the theme's screenPadding/medium/screenPadding (start/top/end) combination
 * is used, so the header's horizontal inset lines up with [TeddSection]'s screen padding instead of
 * sitting 4dp inside it.
 * @param contentPadding Padding around [content]; null means the theme's small/medium
 * (top/bottom) combination is used.
 * @param content The section's rows, laid out in a [Column].
 */
@Composable
fun TeddOptionGroup(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    isSelectableGroup: Boolean = false,
    headerPadding: PaddingValues? = null,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val resolvedHeaderPadding = headerPadding ?: PaddingValues(
        start = spacing.screenPadding,
        top = spacing.medium,
        end = spacing.screenPadding,
    )
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        top = spacing.small,
        bottom = spacing.medium,
    )
    val contentModifier = if (isSelectableGroup) {
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(resolvedContentPadding)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(resolvedContentPadding)
    }
    val colors = teddReaderColors()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface),
    ) {
        CompositionLocalProvider(LocalContentColor provides teddReaderColors().onSurface) {
            if (title != null || description != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(resolvedHeaderPadding),
                ) {
                    title?.let {
                        TeddText(
                            text = it,
                            style = typography.titleMedium,
                        )
                    }
                    if (description != null) {
                        TeddText(
                            text = description,
                            modifier = Modifier.padding(top = if (title != null) spacing.xxSmall else 0.dp),
                            style = typography.settingDescription,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Column(
                modifier = contentModifier,
                content = content,
            )
        }
    }
}

/** Compose preview rendering [TeddOptionGroup] with a title, description, and two [TeddRadioRow]s. */
@Preview
@Composable
private fun TeddOptionGroupPreview() {
    TeddPreviewSurface {
        TeddOptionGroup(
            title = "Reading direction",
            description = "Choose how pages flow through the reader.",
        ) {
            TeddRadioRow(title = "Horizontal", selected = true, onClick = {})
            TeddRadioRow(title = "Vertical", selected = false, onClick = {})
        }
    }
}
