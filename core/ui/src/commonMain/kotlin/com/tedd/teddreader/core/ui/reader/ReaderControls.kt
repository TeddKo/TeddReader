package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIconButton

@Composable
fun ReaderTopControls(
    title: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = spacing.small,
            vertical = spacing.xxSmall,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            }

            Text(
                text = title,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = typography.documentTitle,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}

@Composable
fun ReaderBottomControls(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    progress: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    val spacing = teddReaderSpacing()

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = spacing.small,
            vertical = spacing.xxSmall,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            if (progress != null) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    progress()
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.xxSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions()
            }
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun ReaderControlsPreview() {
    val style = ReaderStyle()
    val spacing = teddReaderSpacing()

    TeddReaderTheme {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            ReaderTopControls(
                title = "A Very Long Chapter Title That Must Ellipsize Cleanly",
                style = style,
                navigationIcon = {
                    TeddIconButton(onClick = {}, contentDescription = "Back") {
                        Text("←", maxLines = 1)
                    }
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Toggle bookmark") {
                        Text("☆", maxLines = 1)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "More options") {
                        Text("⋮", maxLines = 1)
                    }
                },
            )

            ReaderBottomControls(
                style = style,
                progress = {
                    ReaderProgressBar(
                        pageIndex = PageIndex(current = 4, total = 240),
                        showPageLabel = true,
                        showPercentLabel = false,
                        compact = true,
                    )
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Previous page") {
                        Text("‹", maxLines = 1)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Next page") {
                        Text("›", maxLines = 1)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Enable auto-scroll") {
                        Text("▶", maxLines = 1)
                    }
                },
            )
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun ReaderControlsDarkPreview() {
    val style = darkReaderStyle()
    val spacing = teddReaderSpacing()

    TeddReaderTheme(darkTheme = true) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            ReaderTopControls(
                title = "A Very Long Chapter Title That Must Ellipsize Cleanly",
                style = style,
                navigationIcon = {
                    TeddIconButton(onClick = {}, contentDescription = "Back") {
                        Text("←", maxLines = 1)
                    }
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Toggle bookmark") {
                        Text("☆", maxLines = 1)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "More options") {
                        Text("⋮", maxLines = 1)
                    }
                },
            )

            ReaderBottomControls(
                style = style,
                progress = {
                    ReaderProgressBar(
                        pageIndex = PageIndex(current = 4, total = 240),
                        showPageLabel = true,
                        showPercentLabel = false,
                        compact = true,
                    )
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Previous page") {
                        Text("‹", maxLines = 1)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Next page") {
                        Text("›", maxLines = 1)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Enable auto-scroll") {
                        Text("▶", maxLines = 1)
                    }
                },
            )
        }
    }
}
