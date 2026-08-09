package com.tedd.teddreader.core.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.icon.TeddIcons

@Composable
fun ReaderTopControls(
    title: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    titleLabel: String? = "Reading",
    navigationIcon: (@Composable () -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.small,
        vertical = DefaultTeddReaderSpacing.xxSmall,
    ),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        windowInsets = windowInsets,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.small),
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.none),
            ) {
                if (!titleLabel.isNullOrBlank()) {
                    Text(
                        text = titleLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = typography.readerCaption,
                    )
                }
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = typography.titleLarge,
                )
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

@Composable
fun ReaderBottomControls(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    progress: (@Composable BoxScope.() -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    contentPadding: PaddingValues = PaddingValues(
        horizontal = DefaultTeddReaderSpacing.small,
        vertical = DefaultTeddReaderSpacing.xxSmall,
    ),
    actions: @Composable RowScope.() -> Unit = {},
) {
    val spacing = teddReaderSpacing()

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        windowInsets = windowInsets,
        dividerAtTop = true,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < 360.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.small),
                ) {
                    if (progress != null) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            progress.invoke(this)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(
                            spacing.xxSmall,
                            Alignment.End,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        actions()
                    }
                }
            } else {
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
                            progress.invoke(this)
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
    }
}

@Composable
private fun ReaderControlsPreviewContent(style: ReaderStyle, darkTheme: Boolean = false) {
    val spacing = teddReaderSpacing()

    TeddReaderTheme(darkTheme = darkTheme) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.small)) {
            ReaderTopControls(
                title = "A Very Long Chapter Title That Must Ellipsize Cleanly",
                style = style,
                navigationIcon = {
                    TeddIconButton(onClick = {}, contentDescription = "Back") {
                        Icon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Toggle bookmark") {
                        Icon(imageVector = TeddIcons.BookmarkOutline, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "More options") {
                        Icon(imageVector = TeddIcons.MoreVert, contentDescription = null)
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
                        Icon(imageVector = TeddIcons.Previous, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Next page") {
                        Icon(imageVector = TeddIcons.Next, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Enable auto-scroll") {
                        Icon(imageVector = TeddIcons.Play, contentDescription = null)
                    }
                },
            )
        }
    }
}

@Preview(widthDp = 360)
@Composable
private fun ReaderControlsPreview() {
    ReaderControlsPreviewContent(style = ReaderStyle())
}

@Preview(widthDp = 280)
@Composable
private fun ReaderControlsNarrowPreview() {
    ReaderControlsPreviewContent(style = ReaderStyle())
}

@Preview(widthDp = 360)
@Composable
private fun ReaderControlsDarkPreview() {
    ReaderControlsPreviewContent(style = darkReaderStyle(), darkTheme = true)
}
