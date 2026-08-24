package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import com.tedd.teddreader.core.designsystem.teddReaderIconography
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * The app's only way to draw an icon, paired with [TeddText] as the two leaf composables every screen
 * is built from.
 *
 * Uses `foundation`'s `Image` rather than Material's `Icon` for the same reason [TeddText] avoids
 * Material's `Text`: keeping `material3` out of feature modules is only enforceable if nothing there
 * needs it. It also pins the icon's box to the app's own icon scale, which Material's `Icon` does not
 * do — that one defaults to whatever the asset declares, so an icon drawn at 24dp beside one drawn at
 * its asset's own size is a mismatch nobody wrote on purpose.
 *
 * The vectors in [TeddIcons] are all drawn in opaque black as a stand-in for "the shape". They are not
 * black on screen: the tint colour filter here replaces every opaque pixel's colour and keeps only
 * alpha, so the tint is what the user actually sees. An icon rendered without this filter would come
 * out black on a dark surface.
 *
 * @param imageVector The vector to draw, normally a member of [TeddIcons].
 * @param contentDescription What a screen reader announces. Pass null only for an icon that adds no
 * information a sighted user does not already get from adjacent text — a purely decorative glyph. A
 * production icon that is the only label for its action must have one.
 * @param modifier Modifier applied to the icon's layout node, before the size is imposed.
 * @param size The icon's box, one side; defaults to the app's medium icon size so icons line up with
 * each other and with the text beside them.
 * @param tint The colour every opaque pixel of the vector becomes. [Color.Unspecified], the default,
 * means inherit the surrounding content colour so an icon matches the label it sits with.
 */
@Composable
fun TeddIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = teddReaderIconography().medium,
    tint: Color = Color.Unspecified,
) {
    val resolvedTint = if (tint == Color.Unspecified) LocalContentColor.current else tint

    Image(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(resolvedTint),
    )
}

/** Compose preview rendering [TeddIcon] at the default size and at the large icon size. */
@Preview
@Composable
private fun TeddIconPreview() {
    TeddPreviewSurface {
        TeddIcon(imageVector = TeddIcons.Back, contentDescription = "Back")
        TeddIcon(
            imageVector = TeddIcons.BookmarkFilled,
            contentDescription = "Bookmarked",
            size = teddReaderIconography().large,
        )
    }
}
