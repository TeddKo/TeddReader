package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * The app's default text input: an [OutlinedTextField] pinned to the app's shape scale and type
 * style, with its container color fixed to the surface color in every state (focused, unfocused, and
 * disabled) so the field never shows Material's default state-dependent container tinting, which
 * would otherwise clash with the surfaces this field is normally placed on (option sheets, forms).
 * Also enforces the same 48dp minimum touch height every other input in this module uses.
 *
 * @param value The field's current text.
 * @param onValueChange Invoked with the new text whenever the user edits the field.
 * @param modifier Modifier applied to the field, after `fillMaxWidth()` and the 48dp minimum height.
 * @param label A floating label shown above the field's content; omitted when null.
 * @param placeholder Text shown when [value] is empty; omitted when null.
 * @param enabled Whether the field accepts input.
 * @param minLines Minimum number of visible text lines.
 * @param maxLines Maximum number of visible text lines before the field scrolls internally.
 */
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
    val colors = teddReaderColors()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = teddReaderSpacing().touchTarget),
        label = label?.let { { TeddText(text = it) } },
        placeholder = placeholder?.let { { TeddText(text = it) } },
        enabled = enabled,
        minLines = minLines,
        maxLines = maxLines,
        shape = teddReaderShapes().medium,
        textStyle = teddReaderTypography().bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surfaceContainerLow,
        ),
    )
}

/**
 * A single-line search input built on [OutlinedTextField], wired for the IME's search action
 * ([ImeAction.Search]) and fixed to the same surface-colored container in every state as
 * [TeddTextField], with room for leading/trailing icon slots (typically a search glyph and a clear
 * button) that [OutlinedTextField] itself only exposes as raw composable slots.
 *
 * @param value The field's current text.
 * @param onValueChange Invoked with the new text whenever the user edits the field.
 * @param modifier Modifier applied to the field, after `fillMaxWidth()` and the 48dp minimum height.
 * @param placeholder Text shown when [value] is empty. Defaults to the literal string `"Search"`,
 * which is not localized — callers in a localized screen should pass a translated string explicitly.
 * @param enabled Whether the field accepts input.
 * @param onSearch Invoked when the user triggers the IME's search action; not wired to any other
 * event.
 * @param leadingContent Content shown at the field's start, typically a search icon; omitted when
 * null.
 * @param trailingContent Content shown at the field's end, typically a clear button; omitted when
 * null.
 */
@Composable
fun TeddSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    enabled: Boolean = true,
    onSearch: (() -> Unit)? = null,
    onClearClick: (() -> Unit)? = null,
    clearDescription: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val colors = teddReaderColors()
    val resolvedTrailingContent: (@Composable () -> Unit)? = when {
        trailingContent != null -> trailingContent
        onClearClick != null && value.isNotEmpty() -> {
            {
                TeddIconButton(
                    onClick = onClearClick,
                    contentDescription = clearDescription ?: "Clear search",
                ) {
                    TeddIcon(
                        imageVector = TeddIcons.Close,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
        else -> null
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = teddReaderSpacing().touchTarget),
        placeholder = { TeddText(text = placeholder) },
        leadingIcon = leadingContent,
        trailingIcon = resolvedTrailingContent,
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        shape = teddReaderShapes().medium,
        textStyle = teddReaderTypography().bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surface,
            unfocusedContainerColor = colors.surface,
            disabledContainerColor = colors.surfaceContainerLow,
        ),
    )
}
