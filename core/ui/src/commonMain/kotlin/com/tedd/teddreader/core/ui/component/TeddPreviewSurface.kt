package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderColors

/**
 * Wraps a `@Preview` in the app's theme and surface colours, so a preview shows the component as the app
 * really draws it. Uses a plain [background] rather than Material's `Surface`, since this scaffolding
 * needs only a fill colour and a content-colour default — no shape, border, or elevation — and the
 * [CompositionLocalProvider] here reproduces the one part of `Surface` that background alone would have
 * dropped: many previewed components (icons and text with an unspecified tint/colour) read their colour
 * from [LocalContentColor] and would otherwise fall back to whatever the ambient default outside this
 * preview happens to be.
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
        val colors = teddReaderColors()
        CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
            Box(modifier = modifier.background(colors.surface).padding(contentPadding)) {
                content()
            }
        }
    }
}
