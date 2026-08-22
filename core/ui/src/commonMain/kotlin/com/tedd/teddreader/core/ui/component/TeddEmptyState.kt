package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * The app's placeholder for a screen or list with nothing in it (an empty library, no search
 * results), centering a title, an optional description, and an optional action button so every such
 * screen shares the same layout instead of each hand-centering its own [Column].
 *
 * @param title The primary message, shown in [teddReaderTypography]'s `documentTitle` style.
 * @param modifier Modifier applied to the state's root.
 * @param description Supporting text shown under [title] in a muted color; omitted when null.
 * @param contentPadding Padding around the whole state.
 * @param action Content shown below the description, typically a [TeddButton] prompting the next
 * step; omitted when null.
 */
@Composable
fun TeddEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.large),
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        Text(
            text = title,
            style = typography.documentTitle,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = typography.settingDescription,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        action?.invoke(this)
    }
}

/** Compose preview rendering [TeddEmptyState] with a title, description, and action button. */
@Preview
@Composable
private fun TeddEmptyStatePreview() {
    TeddPreviewSurface {
        TeddEmptyState(
            title = "No books yet",
            description = "Add TXT, PDF, or EPUB files to start reading.",
            action = {
                TeddButton(text = "Open file", onClick = {})
            },
        )
    }
}
