package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.TeddReaderTheme

@Composable
internal fun TeddPreviewSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    TeddReaderTheme {
        TeddSurface(modifier = modifier.padding(contentPadding)) {
            content()
        }
    }
}

