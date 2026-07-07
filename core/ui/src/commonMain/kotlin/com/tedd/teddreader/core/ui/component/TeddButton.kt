package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderElevation
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun TeddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.large,
        vertical = DefaultTeddReaderSpacing.small,
    ),
) {
    val typography = teddReaderTypography()
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = teddReaderShapes().medium,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = teddReaderElevation().xSmall,
            pressedElevation = teddReaderElevation().small,
        ),
        contentPadding = contentPadding,
    ) {
        Text(text = text, style = typography.labelLarge)
    }
}

@Preview
@Composable
private fun TeddButtonPreview() {
    TeddPreviewSurface {
        TeddButton(
            text = "Open document",
            onClick = {},
        )
    }
}
