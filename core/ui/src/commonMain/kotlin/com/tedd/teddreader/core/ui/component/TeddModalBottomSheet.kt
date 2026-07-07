package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeddModalBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.sheetPadding,
        vertical = DefaultTeddReaderSpacing.large,
    ),
    content: @Composable () -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
        ) {
            Text(
                text = title,
                style = typography.titleLarge,
            )
            if (description != null) {
                Text(
                    text = description,
                    modifier = Modifier.padding(top = spacing.xxSmall),
                    style = typography.settingDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(modifier = Modifier.padding(top = spacing.medium)) {
                content()
            }
        }
    }
}

@Preview
@Composable
private fun TeddModalBottomSheetContentPreview() {
    TeddPreviewSurface {
        TeddModalBottomSheet(
            title = "Sort by",
            onDismissRequest = {},
            content = {
                TeddButton(text = "Recent", onClick = {})
            },
        )
    }
}
