package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderElevation
import com.tedd.teddreader.core.designsystem.teddReaderShapes

@Composable
fun TeddSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = teddReaderShapes().medium,
        tonalElevation = teddReaderElevation().xSmall,
        content = content,
    )
}

@Preview
@Composable
private fun TeddSurfacePreview() {
    TeddPreviewSurface {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            TeddButton(text = "Inside surface", onClick = {})
        }
    }
}
