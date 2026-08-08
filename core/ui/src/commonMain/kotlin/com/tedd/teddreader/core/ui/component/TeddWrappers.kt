package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

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

@Composable
fun TeddListItem(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.medium,
        vertical = DefaultTeddReaderSpacing.small,
    ),
    showDivider: Boolean = true,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .run {
            if (onLongClick != null) {
                combinedClickable(
                    enabled = enabled,
                    onClick = onClick ?: {},
                    onLongClick = onLongClick,
                )
            } else if (onClick != null) {
                clickable(enabled = enabled, onClick = onClick)
            } else {
                this
            }
        }

    Row(
        modifier = if (showDivider) {
            rowModifier
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val y = size.height - strokeWidth / 2f
                    drawLine(
                        color = dividerColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth,
                    )
                }
                .padding(contentPadding)
        } else {
            rowModifier.padding(contentPadding)
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
    ) {
        leadingContent?.invoke(this)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = typography.settingTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (supportingText != null) {
                Text(
                    text = supportingText,
                    style = typography.settingDescription,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailingContent?.invoke(this)
    }
}

@Composable
fun TeddInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xxSmall),
    ) {
        Text(
            text = label,
            style = typography.documentMeta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = typography.settingTitle,
        )
    }
}

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

@Composable
fun TeddTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        shape = teddReaderShapes().medium,
        textStyle = teddReaderTypography().bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

@Composable
fun TeddSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        placeholder = { Text(placeholder) },
        leadingIcon = leadingContent,
        trailingIcon = trailingContent,
        singleLine = true,
        enabled = enabled,
        shape = teddReaderShapes().medium,
        textStyle = teddReaderTypography().bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    )
}

@Composable
fun TeddScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = topBar,
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun TeddTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.medium,
        vertical = DefaultTeddReaderSpacing.small,
    ),
    actions: @Composable RowScope.() -> Unit = {},
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(teddReaderSpacing().small),
        ) {
            navigationIcon?.invoke(this)
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = teddReaderTypography().titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            actions()
        }
    }
}
