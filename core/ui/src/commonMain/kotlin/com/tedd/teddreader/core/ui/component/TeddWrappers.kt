package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tedd.teddreader.core.designsystem.teddReaderElevation
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

@Composable
fun TeddCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = teddReaderShapes().large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = teddReaderElevation().small,
    ) {
        content()
    }
}

@Composable
fun TeddChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val spacing = teddReaderSpacing()
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier.then(clickableModifier),
        shape = teddReaderShapes().extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = teddReaderElevation().none,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = spacing.medium,
                vertical = spacing.xSmall,
            ),
            style = teddReaderTypography().labelLarge,
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
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier
    }

    Surface(
        modifier = modifier.fillMaxWidth().then(clickableModifier),
        shape = teddReaderShapes().medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = teddReaderElevation().xSmall,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = spacing.medium,
                vertical = spacing.small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            if (leadingContent != null) {
                leadingContent()
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = typography.settingTitle)
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = typography.settingDescription,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailingContent != null) {
                trailingContent()
            }
        }
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
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = typography.settingDescription)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = typography.settingTitle)
    }
}

@Composable
fun TeddErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = teddReaderShapes().medium,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = teddReaderElevation().xSmall,
    ) {
        Column(
            modifier = Modifier.padding(spacing.medium),
            verticalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            Text(text = message, style = typography.settingTitle)
            action?.invoke()
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
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
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
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        leadingIcon = leadingContent,
        trailingIcon = trailingContent,
        singleLine = true,
        enabled = enabled,
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
        topBar = topBar,
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun TeddTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = teddReaderElevation().xSmall,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = teddReaderSpacing().medium,
                vertical = teddReaderSpacing().small,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(teddReaderSpacing().small),
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = teddReaderTypography().titleLarge,
            )
            actions()
        }
    }
}
