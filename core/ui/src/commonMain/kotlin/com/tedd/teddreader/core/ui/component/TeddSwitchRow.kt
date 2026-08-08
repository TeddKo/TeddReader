package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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

@Composable
fun TeddSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
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
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
    }
}

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
