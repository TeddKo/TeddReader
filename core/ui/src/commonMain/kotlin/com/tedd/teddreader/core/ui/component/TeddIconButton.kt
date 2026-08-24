package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * An icon-only tap target that pins Material's [IconButton] to the 48dp minimum touch size the app
 * requires everywhere, since [IconButton] on its own only guarantees that size when the icon content
 * itself already fills it — a smaller icon inside would otherwise shrink the tappable area below the
 * accessibility minimum. Also wires up [contentDescription] as the button's own accessibility label
 * instead of leaving it to whatever icon [content] happens to render, so callers do not need to
 * remember to label the icon itself.
 *
 * @param onClick Invoked when the button is tapped; never invoked while [enabled] is false.
 * @param modifier Modifier applied to the underlying [IconButton], after the enforced 48dp minimum
 * size is applied.
 * @param enabled Whether the button responds to taps; false also switches it to disabled colors.
 * @param contentDescription Accessibility label read for this button; when null or blank, no
 * `contentDescription` semantics are attached (the icon must supply its own, or remain unlabeled).
 * @param content The icon (or other small content) shown inside the button.
 */
@Composable
fun TeddIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    content: @Composable () -> Unit,
) {
    val semanticsModifier = if (!contentDescription.isNullOrBlank()) {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    } else {
        Modifier
    }

    IconButton(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .then(semanticsModifier),
        enabled = enabled,
        content = content,
    )
}

/** Compose preview rendering [TeddIconButton] with a bookmark icon and its accessibility label. */
@Preview
@Composable
private fun TeddIconButtonPreview() {
    TeddPreviewSurface {
        TeddIconButton(
            onClick = {},
            contentDescription = "Favorite",
        ) {
            Icon(imageVector = TeddIcons.BookmarkFilled, contentDescription = null)
        }
    }
}
