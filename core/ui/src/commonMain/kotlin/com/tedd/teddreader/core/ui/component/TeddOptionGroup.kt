package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun TeddOptionGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    TeddSurface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.medium)) {
            Text(
                text = title,
                style = typography.titleMedium,
            )
            if (description != null) {
                Text(
                    text = description,
                    modifier = Modifier.padding(top = spacing.xxSmall),
                    style = typography.settingDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.padding(top = spacing.small)) {
                content()
            }
        }
    }
}

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
