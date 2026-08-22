package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.TeddReaderTheme

/**
 * Wraps a `@Preview` in the app's theme and surface colours, so a preview shows the component as the app
 * really draws it.
 *
 * Internal on purpose: it is scaffolding for previews in this module, not something a screen should compose.
 *
 * @param modifier applied to the surface.
 * @param contentPadding inset around the previewed content, so a component's edges are visible.
 * @param content the component being previewed.
 */
@Composable
internal fun TeddPreviewSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    TeddReaderTheme {
        Surface(
            modifier = modifier.padding(contentPadding),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            content()
        }
    }
}
