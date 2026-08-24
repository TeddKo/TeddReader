package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.teddReaderColors

/**
 * A hairline separating two rows or two groups.
 *
 * Replaces Material's `HorizontalDivider` so that, like [TeddText] and [TeddIcon], nothing outside
 * `core/ui` needs a `material3` import. It also defaults to the app's divider role rather than
 * Material's, which matters because this app distinguishes two quiet line colours that Material treats
 * as one: `outlineVariant` for a separator between peers, and `outlineSubtle` for the frame around a
 * container. A divider drawn in the container colour reads as a stray container edge.
 *
 * Prefer whitespace to a divider. The app's visual language builds hierarchy from spacing and type
 * weight, so a divider earns its place only where two adjacent rows would otherwise be ambiguous — not
 * as decoration between every item in a list.
 *
 * @param modifier Modifier applied to the divider. The divider fills the width it is given, so a
 * caller that wants an inset line supplies that inset here rather than expecting a padding parameter.
 * @param thickness The line's height. Left as a raw hairline rather than a spacing token because one
 * device pixel is not a step on a spacing scale.
 * @param color The line's colour; defaults to the palette's separator role.
 */
@Composable
fun TeddDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = teddReaderColors().outlineVariant,
) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color),
    )
}

/** Compose preview rendering [TeddDivider] between two labels. */
@Preview
@Composable
private fun TeddDividerPreview() {
    TeddPreviewSurface {
        TeddText(text = "Above the line")
        TeddDivider()
        TeddText(text = "Below the line")
    }
}
