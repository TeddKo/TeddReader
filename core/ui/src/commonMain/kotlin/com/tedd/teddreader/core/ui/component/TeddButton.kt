package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderTypography

enum class TeddButtonEmphasis {
    Primary,
    Secondary,
    Text,
    Destructive,
}

@Composable
fun TeddButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasis: TeddButtonEmphasis = TeddButtonEmphasis.Primary,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.large,
        vertical = DefaultTeddReaderSpacing.small,
    ),
) {
    val typography = teddReaderTypography()
    val shapedModifier = modifier.defaultMinSize(minHeight = 48.dp)
    val shape = teddReaderShapes().medium
    val label: @Composable () -> Unit = {
        Text(text = text, style = typography.labelLarge)
    }

    when (emphasis) {
        TeddButtonEmphasis.Primary -> Button(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
            contentPadding = contentPadding,
            content = { label() },
        )

        TeddButtonEmphasis.Secondary -> OutlinedButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            contentPadding = contentPadding,
            content = { label() },
        )

        TeddButtonEmphasis.Text -> TextButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(),
            content = { label() },
        )

        TeddButtonEmphasis.Destructive -> TextButton(
            onClick = onClick,
            modifier = shapedModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
            ),
            content = { label() },
        )
    }
}

@Composable
fun TeddChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.medium,
        vertical = DefaultTeddReaderSpacing.xSmall,
    ),
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val shape = RoundedCornerShape(percent = 50)

    if (onClick != null) {
        Surface(
            selected = selected,
            onClick = onClick,
            modifier = modifier.semantics {
                role = Role.Button
            },
            enabled = enabled,
            shape = shape,
            color = backgroundColor,
            contentColor = contentColor,
            tonalElevation = 0.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                text = text,
                modifier = Modifier.padding(contentPadding),
                style = teddReaderTypography().labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Text(
            text = text,
            modifier = (if (selected) modifier.semantics { this.selected = true } else modifier)
                .clip(shape)
                .background(backgroundColor)
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
                .padding(contentPadding),
            color = contentColor,
            style = teddReaderTypography().labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
