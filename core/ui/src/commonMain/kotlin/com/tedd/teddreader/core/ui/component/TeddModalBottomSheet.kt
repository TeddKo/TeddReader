package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.extension.consumeUnconsumedVerticalScroll

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TeddModalBottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentPadding: PaddingValues = PaddingValues(
        start = DefaultTeddReaderSpacing.sheetPadding,
        end = DefaultTeddReaderSpacing.sheetPadding,
        bottom = DefaultTeddReaderSpacing.large,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .consumeUnconsumedVerticalScroll(),
        ) {
            Column(
                modifier = Modifier.padding(
                    start = DefaultTeddReaderSpacing.sheetPadding,
                    top = DefaultTeddReaderSpacing.large,
                    end = DefaultTeddReaderSpacing.sheetPadding,
                ),
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
            }
            Spacer(modifier = Modifier.height(spacing.medium))
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeddModalBottomSheetContentPreview() {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    TeddPreviewSurface {
        TeddModalBottomSheet(
            title = "Sort by",
            onDismissRequest = {},
            sheetState = sheetState,
            content = {
                TeddButton(text = "Recent", onClick = {})
            },
        )
    }
}
