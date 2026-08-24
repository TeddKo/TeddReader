package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.extension.teddClickable
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * An icon-only tap target built on [teddClickable] rather than Material's `IconButton`, because
 * `IconButton`'s ripple derives its colour from `contentColor`, which this app's ripple policy
 * forbids (see [teddClickable]). [CircleShape] is passed as the ripple/clip shape because that is the
 * shape `IconButton` itself resolves to by default, so the pressed feedback keeps its round footprint.
 * The [sizeIn] boundary pins the 48dp minimum touch size the app requires everywhere, since a small
 * icon's own bounds would otherwise shrink the tappable area below the accessibility minimum. Also
 * wires up [contentDescription] as the button's own accessibility label instead of leaving it to
 * whatever icon [content] happens to render, so callers do not need to remember to label the icon
 * itself.
 *
 * @param onClick Invoked when the button is tapped; never invoked while [enabled] is false.
 * @param modifier Modifier applied to the button's root, after the enforced 48dp minimum size is
 * applied.
 * @param enabled Whether the button responds to taps; false also removes its click semantics.
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
    val spacing = teddReaderSpacing()
    val semanticsModifier = if (!contentDescription.isNullOrBlank()) {
        Modifier.semantics {
            this.contentDescription = contentDescription
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .sizeIn(minWidth = spacing.touchTarget, minHeight = spacing.touchTarget)
            .teddClickable(onClick = onClick, shape = CircleShape, enabled = enabled, role = Role.Button)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
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
            TeddIcon(imageVector = TeddIcons.BookmarkFilled, contentDescription = null)
        }
    }
}
