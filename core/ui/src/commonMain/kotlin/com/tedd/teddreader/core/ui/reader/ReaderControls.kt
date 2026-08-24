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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.darkReaderStyle
import com.tedd.teddreader.core.designsystem.TeddReaderTheme
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.icon.TeddIcons

/**
 * The reader's top bar: where the reader is in the book, and the actions that act on it.
 *
 * Two lines of text rather than one — a quiet label over the book's own title — because the title is what the
 * reader needs to recognise the page and the label is what tells them which chapter it belongs to. Both are
 * single-line and ellipsised: a long chapter title must not push the actions off the bar.
 *
 * @param title the text that identifies what is being read; the reader feature passes the chapter title,
 * which stays pinned for every page of a chapter rather than only its first.
 * @param style the reader's style, which decides the bar's own colours (see [ReaderChromeSurface]).
 * @param modifier applied to the bar; it fills its parent's width.
 * @param titleLabel the quiet line above [title]; null or blank omits it entirely rather than leaving a gap.
 * @param navigationIcon the leading control, normally back; null leaves the space to the title.
 * @param windowInsets the system insets the bar must keep clear — the status bar on a top bar.
 * @param contentPadding inset around the bar's row, inside those insets; null means the theme's
 * small/xxSmall combination is used.
 * @param actions trailing controls, laid out in a row at the bar's end.
 * @param titleAtEnd true aligns both lines of text to the end instead of the start, for a right-to-left
 * arrangement of the same bar.
 */
@Composable
fun ReaderTopControls(
    title: String,
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    titleLabel: String? = "Reading",
    navigationIcon: (@Composable () -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    contentPadding: PaddingValues? = null,
    actions: @Composable RowScope.() -> Unit = {},
    titleAtEnd: Boolean = false,
) {
    val spacing = teddReaderSpacing()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.small,
        vertical = spacing.xxSmall,
    )
    val typography = teddReaderTypography()

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = resolvedContentPadding,
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
                horizontalAlignment = if (titleAtEnd) Alignment.End else Alignment.Start,
            ) {
                if (!titleLabel.isNullOrBlank()) {
                    TeddText(
                        text = titleLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (titleAtEnd) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        style = typography.readerCaption,
                    )
                }
                TeddText(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (titleAtEnd) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
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

/**
 * The reader's bottom bar: progress, and the actions that move through the book.
 *
 * Its hairline sits on the *upper* edge (`dividerAtTop`), which is what separates it from the page above
 * rather than from the device's own edge below.
 *
 * @param style the reader's style, which decides the bar's colours.
 * @param modifier applied to the bar; it fills its parent's width.
 * @param progress the progress indicator to draw, in the bar's own box scope so it can size itself; null for
 * a bar that shows only actions.
 * @param windowInsets the system insets the bar must keep clear — the navigation bar on a bottom bar.
 * @param contentPadding inset around the bar's content, inside those insets; null means the theme's
 * small/xxSmall combination is used.
 * @param actions the bar's controls, laid out in a row.
 */
@Composable
fun ReaderBottomControls(
    style: ReaderStyle,
    modifier: Modifier = Modifier,
    progress: (@Composable BoxScope.() -> Unit)? = null,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    contentPadding: PaddingValues? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()
    val resolvedContentPadding = contentPadding ?: PaddingValues(
        horizontal = spacing.small,
        vertical = spacing.xxSmall,
    )

    ReaderChromeSurface(
        style = style,
        modifier = modifier.fillMaxWidth(),
        contentPadding = resolvedContentPadding,
        windowInsets = windowInsets,
        dividerAtTop = true,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth < breakpoints.compact) {
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

/**
 * Both bars around a page, which is the only arrangement worth previewing — each bar's colours and hairline
 * only make sense against the paper between them.
 *
 * @param style the reader style to draw the bars and page with.
 * @param darkTheme whether the surrounding app theme is dark, which the bars do NOT follow — they follow
 * [style] — so this preview shows that independence rather than assuming it.
 */
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
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    TeddIconButton(onClick = {}, contentDescription = "Toggle bookmark") {
                        TeddIcon(imageVector = TeddIcons.BookmarkOutline, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "More options") {
                        TeddIcon(imageVector = TeddIcons.MoreVert, contentDescription = null)
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
                        TeddIcon(imageVector = TeddIcons.Previous, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Next page") {
                        TeddIcon(imageVector = TeddIcons.Next, contentDescription = null)
                    }
                    TeddIconButton(onClick = {}, contentDescription = "Enable auto-scroll") {
                        TeddIcon(imageVector = TeddIcons.Play, contentDescription = null)
                    }
                },
            )
        }
    }
}

/** Both bars at a normal width on day paper. */
@Preview(widthDp = 360)
@Composable
private fun ReaderControlsPreview() {
    ReaderControlsPreviewContent(style = ReaderStyle())
}

/** 280dp wide, where a long title has to ellipsise instead of pushing the actions out. */
@Preview(widthDp = 280)
@Composable
private fun ReaderControlsNarrowPreview() {
    ReaderControlsPreviewContent(style = ReaderStyle())
}

/** Night paper under a dark app theme, where the hairline and the caption are hardest to keep visible. */
@Preview(widthDp = 360)
@Composable
private fun ReaderControlsDarkPreview() {
    ReaderControlsPreviewContent(style = darkReaderStyle(), darkTheme = true)
}
