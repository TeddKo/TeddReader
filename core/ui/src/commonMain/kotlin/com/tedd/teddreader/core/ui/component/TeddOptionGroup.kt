package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun TeddOptionGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(headerPadding),
            ) {
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
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                content = content,
            )
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
