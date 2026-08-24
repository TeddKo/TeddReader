package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
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
 * @param headerPadding Padding around the header block (only relevant when [title] or [description]
 * is non-null).
 * @param contentPadding Padding around [content].
 * @param content The section's rows, laid out in a [Column].
 */
@Composable
fun TeddOptionGroup(
    title: String?,
    modifier: Modifier = Modifier,
    description: String? = null,
    isSelectableGroup: Boolean = false,
    headerPadding: PaddingValues = PaddingValues(
        start = DefaultTeddReaderSpacing.medium,
        top = DefaultTeddReaderSpacing.medium,
        end = DefaultTeddReaderSpacing.medium,
    ),
    contentPadding: PaddingValues = PaddingValues(
        top = DefaultTeddReaderSpacing.small,
        bottom = DefaultTeddReaderSpacing.medium,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val contentModifier = if (isSelectableGroup) {
        Modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(contentPadding)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(contentPadding)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (title != null || description != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(headerPadding),
                ) {
                    title?.let {
                        Text(
                            text = it,
                            style = typography.titleMedium,
                        )
                    }
                    if (description != null) {
                        Text(
                            text = description,
                            modifier = Modifier.padding(top = if (title != null) spacing.xxSmall else 0.dp),
                            style = typography.settingDescription,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
