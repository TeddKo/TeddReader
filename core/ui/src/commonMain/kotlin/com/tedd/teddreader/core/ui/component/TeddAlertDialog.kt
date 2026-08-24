package com.tedd.teddreader.core.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * A modal dialog asking the user to confirm or cancel, styled to the app's tokens.
 *
 * Wraps Material's dialog rather than rebuilding it. A dialog has to place itself in a platform
 * window above everything else, trap focus while open, restore focus on dismissal, and route the back
 * gesture — behaviour that is easy to get subtly wrong and invisible until an accessibility service or
 * a hardware keyboard exercises it. Material already handles all of that correctly, so the value here
 * is in pinning the surface, shape, and type to this app's scales instead of Material's defaults, and
 * in keeping the Material import inside this module.
 *
 * Reserve this for a decision the user must make before anything else continues — a destructive
 * confirmation, a required choice. Anything a user can browse, dismiss, or ignore belongs in a
 * [TeddModalBottomSheet], which does not steal focus the same way.
 *
 * @param onDismissRequest Invoked when the user dismisses without deciding — tapping outside, or the
 * back gesture. Must leave the underlying state unchanged; a dialog dismissed this way is a
 * cancellation, not a confirmation.
 * @param confirmButton The action that carries out what the dialog asked about. For a destructive
 * action, give it destructive emphasis so it does not read as the safe default.
 * @param modifier Modifier applied to the dialog's surface.
 * @param dismissButton The explicit cancel action; omitted when null. Supply one for any destructive
 * dialog, because tapping outside is not a discoverable way to decline.
 * @param title The dialog's heading, stated as what is about to happen; omitted when null.
 * @param text The dialog's body, carrying the consequence the user is agreeing to; omitted when null.
 */
@Composable
fun TeddAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: String? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val colors = teddReaderColors()
    val typography = teddReaderTypography()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title?.let { { TeddText(text = it, style = typography.titleLarge) } },
        text = text,
        shape = teddReaderShapes().large,
        containerColor = colors.surfaceContainerLow,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
    )
}

/** Compose preview rendering [TeddAlertDialog] as a destructive confirmation. */
@Preview
@Composable
private fun TeddAlertDialogPreview() {
    TeddAlertDialog(
        onDismissRequest = {},
        title = "Delete document?",
        text = { TeddText(text = "The file on your device is not affected.") },
        confirmButton = {
            TeddButton(
                text = "Delete",
                onClick = {},
                emphasis = TeddButtonEmphasis.Destructive,
            )
        },
        dismissButton = { TeddButton(text = "Cancel", onClick = {}, emphasis = TeddButtonEmphasis.Text) },
    )
}
