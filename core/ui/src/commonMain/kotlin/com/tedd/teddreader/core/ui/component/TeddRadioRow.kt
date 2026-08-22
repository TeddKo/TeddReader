package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * A settings row that pairs a title/description with a trailing [RadioButton], where the whole row
 * (not just the radio dot) is the tap target — the row itself carries the `selectable` modifier with
 * `role = Role.RadioButton`, and the [RadioButton] passed `onClick = null` so it renders visually but
 * does not register its own, smaller, tap target. Meant to be used in a group (see
 * [TeddOptionGroup]) where each row's [onClick] selects that row's option.
 *
 * @param title The row's primary text, shown in [teddReaderTypography]'s `settingTitle` style.
 * @param selected Whether this row's radio button is drawn selected.
 * @param onClick Invoked when the row is tapped, to select this row's option; not invoked while
 * [enabled] is false.
 * @param modifier Modifier applied to the row's root.
 * @param description A second line shown under [title] in a muted color; omitted when null.
 * @param enabled Whether the row responds to taps; false also switches the radio button to disabled
 * colors.
 * @param contentPadding Padding between the row's edge and its content.
 */
@Composable
fun TeddRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.medium,
        vertical = DefaultTeddReaderSpacing.small,
    ),
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = typography.settingTitle)
                if (description != null) {
                    Text(
                        text = description,
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(spacing.medium))
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
        }
    }
}

/** Compose preview rendering [TeddRadioRow] selected, with a title and description. */
@Preview
@Composable
private fun TeddRadioRowPreview() {
    TeddPreviewSurface {
        TeddRadioRow(
            title = "Horizontal",
            description = "Turn pages side to side.",
            selected = true,
            onClick = {},
        )
    }
}
