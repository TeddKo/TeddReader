package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.teddToggleable

/**
 * A settings row that pairs a title/description with a trailing [Switch], where the whole row (not
 * just the switch thumb) is the tap target — the row itself carries [teddToggleable] with
 * `role = Role.Switch`, and the [Switch] passed `onCheckedChange = null` so it renders visually but
 * does not register its own, smaller, tap target. Without that split, tapping anywhere but the small
 * switch control itself would do nothing, which fails the row-sized touch target this app's settings
 * screens rely on everywhere else (see [TeddRadioRow], [TeddCheckboxRow]). [Switch] itself is kept
 * rather than rebuilt, since `onCheckedChange = null` already makes it non-interactive with no ripple
 * of its own — only its `colors` are pinned to this app's tokens instead of the ambient Material
 * scheme, so the thumb and track stay correct if that scheme ever drifts from [teddReaderColors].
 *
 * @param title The row's primary text, shown in [teddReaderTypography]'s `settingTitle` style.
 * @param checked Whether the switch is drawn on.
 * @param onCheckedChange Invoked with the new checked state when the row is tapped; not invoked
 * while [enabled] is false.
 * @param modifier Modifier applied to the row's root.
 * @param description A second line shown under [title] in a muted color; omitted when null.
 * @param enabled Whether the row responds to taps; false also switches the switch to disabled colors.
 * @param contentPadding Padding between the row's edge and its content; null means the theme's
 * screenPadding/small combination is used, so the row's horizontal inset lines up with
 * [TeddSection]'s screen padding instead of sitting 4dp inside it.
 */
@Composable
fun TeddSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues? = null,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.screenPadding,
        vertical = spacing.small,
    )
    val typography = teddReaderTypography()
    val colors = teddReaderColors()

    CompositionLocalProvider(LocalContentColor provides teddReaderColors().onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = spacing.rowHeight)
                .background(colors.surface)
                .teddToggleable(
                    value = checked,
                    onValueChange = onCheckedChange,
                    enabled = enabled,
                    role = Role.Switch,
                )
                .padding(resolvedContentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                TeddText(text = title, style = typography.settingTitle)
                if (description != null) {
                    TeddText(
                        text = description,
                        style = typography.settingDescription,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(spacing.medium))
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onPrimary,
                    checkedTrackColor = colors.primary,
                    checkedBorderColor = colors.primary,
                    uncheckedThumbColor = colors.outline,
                    uncheckedTrackColor = colors.surfaceContainerHighest,
                    uncheckedBorderColor = colors.outline,
                ),
            )
        }
    }
}

/** Compose preview rendering [TeddSwitchRow] checked, with a title and description. */
@Preview
@Composable
private fun TeddSwitchRowPreview() {
    TeddPreviewSurface {
        TeddSwitchRow(
            title = "Keep screen on",
            description = "Prevent the display from sleeping while reading.",
            checked = true,
            onCheckedChange = {},
        )
    }
}
