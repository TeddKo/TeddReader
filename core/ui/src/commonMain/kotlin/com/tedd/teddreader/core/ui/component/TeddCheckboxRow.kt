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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalContentColor
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
 * A settings row that pairs a title/description with a trailing [Checkbox], where the whole row
 * (not just the checkbox glyph) is the tap target — the row itself carries [teddToggleable] with
 * `role = Role.Checkbox`, and the [Checkbox] passed `onCheckedChange = null` so it renders visually
 * but does not register its own, smaller, tap target. Same pattern as [TeddSwitchRow] and
 * [TeddRadioRow], for the checkbox case. [Checkbox] itself is kept rather than rebuilt — a null
 * `onCheckedChange` already removes its own ripple — with only its `colors` pinned to this app's
 * tokens instead of the ambient Material scheme.
 *
 * @param title The row's primary text, shown in [teddReaderTypography]'s `settingTitle` style.
 * @param checked Whether the checkbox is drawn checked.
 * @param onCheckedChange Invoked with the new checked state when the row is tapped; not invoked
 * while [enabled] is false.
 * @param modifier Modifier applied to the row's root.
 * @param description A second line shown under [title] in a muted color; omitted when null.
 * @param enabled Whether the row responds to taps; false also switches the checkbox to disabled
 * colors.
 * @param contentPadding Padding between the row's edge and its content; null means the theme's
 * screenPadding/small combination is used, so the row's horizontal inset lines up with
 * [TeddSection]'s screen padding instead of sitting 4dp inside it.
 */
@Composable
fun TeddCheckboxRow(
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
                    role = Role.Checkbox,
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
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = colors.primary,
                    uncheckedColor = colors.onSurfaceVariant,
                    checkmarkColor = colors.onPrimary,
                ),
            )
        }
    }
}

/** Compose preview rendering [TeddCheckboxRow] unchecked, with a title and description. */
@Preview
@Composable
private fun TeddCheckboxRowPreview() {
    TeddPreviewSurface {
        TeddCheckboxRow(
            title = "Show page numbers",
            description = "Display current and total page counts.",
            checked = false,
            onCheckedChange = {},
        )
    }
}
