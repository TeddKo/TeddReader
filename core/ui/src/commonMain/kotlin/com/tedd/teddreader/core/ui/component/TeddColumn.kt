package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * The app's card surface: one clipped, bordered container that every list row and panel sits in.
 *
 * Drawn with modifiers on the `Column` that already arranges the content rather than wrapping it in a
 * `Surface`, so a card is one layout node instead of two. It also pins the content colour, so a caller never
 * has to remember which `on…` role matches the card's own background.
 *
 * @param modifier applied to the card itself; a caller sizes and positions it from outside.
 * @param content the card's children, laid out in a column and drawn in the card's content colour.
 */
@Composable
fun TeddCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = teddReaderShapes().medium

    Column(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

/**
 * The one way a failure is shown inline, so every screen reports an error the same way.
 *
 * Its own colour roles rather than a plain red: the container carries the error tint with a hairline border,
 * and the content colour is set once for both the message and any action, so a caller cannot leave a button
 * unreadable on the tinted background.
 *
 * @param message what went wrong, in the reader's language.
 * @param modifier applied to the banner; it fills its parent's width by default.
 * @param contentPadding inset around the message and action.
 * @param action an optional retry or dismiss control, composed under the message inside the banner's own
 * column scope.
 */
@Composable
fun TeddErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DefaultTeddReaderSpacing.medium),
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val shape = teddReaderShapes().small

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onErrorContainer) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.errorContainer)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.15f)), shape)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(text = message, style = typography.settingTitle)
            action?.invoke(this)
        }
    }
}
