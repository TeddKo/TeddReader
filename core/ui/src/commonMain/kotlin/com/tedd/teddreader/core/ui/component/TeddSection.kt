package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.TeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * What kind of block a [TeddSection] is, and therefore how much air sits above it.
 *
 * A closed set of four on purpose. Before this existed, one screen expressed the same idea four
 * different ways — an anonymous `Column` for its header block, named composables for its collections,
 * inline code on other screens, and an option group in the sheets — and every one of them chose its own
 * gaps. Naming the four kinds is what makes "which block is this?" a question with an answer.
 *
 * The distinction that matters is [Collection] versus the rest: a collection may run its body to the
 * screen edge while its heading stays aligned with the other sections, and nothing else may.
 */
enum class TeddSectionKind {
    /**
     * The screen's identity and its top-level action. Exactly one per screen, first in the scroll.
     * Carries no gap above it because nothing precedes it.
     */
    Masthead,

    /**
     * A banner, an error, an empty state, or a loading indicator — conditional, and mutually exclusive
     * with the others of its kind. Sits closer to its neighbours than a real section does, because it is
     * a state of the content around it rather than a section of its own.
     */
    Status,

    /**
     * A collection of documents, folders, or results, with an optional heading above its body. The only
     * kind whose body may ignore the screen's horizontal inset, so a horizontally scrolling row of cards
     * can run to the edge.
     */
    Collection,

    /**
     * Grouped controls — filters, sort options, settings. Reads as a set of choices rather than as
     * content, so its body always keeps the screen inset.
     */
    Form,
}

/**
 * The gap above a section of this kind.
 *
 * @receiver the kind of section being spaced.
 * @param spacing the theme's spacing scale.
 * @return the gap to place above the section: none for the first block on a screen, the tighter item gap
 * for a transient state, and the full section gap for anything that reads as its own section.
 */
private fun TeddSectionKind.topGap(spacing: TeddReaderSpacing) = when (this) {
    TeddSectionKind.Masthead -> spacing.none
    TeddSectionKind.Status -> spacing.itemGap
    TeddSectionKind.Collection, TeddSectionKind.Form -> spacing.sectionGap
}

/**
 * One block of a screen, owning the three things that used to be decided separately at every call site:
 * the gap above it, the horizontal inset around it, and how its heading is drawn.
 *
 * Screens hand over content and a [kind]; they do not pick the gap above the section. That inversion is
 * the point. When each block chose its own gaps, every structural gap on the home screen ended up as the
 * same 24dp value, which made a section boundary and the gap between two items inside a section visually
 * identical — the screen read as one flat list and its hierarchy was invisible. Routing every block
 * through here means [TeddSectionKind] decides, and the three gap tokens stay distinguishable.
 * Arrangement inside the body column is the caller's to supply via [verticalArrangement].
 *
 * The horizontal inset lives here rather than on the scroll container, which is the other half of the
 * fix. A `LazyColumn` that applies horizontal `contentPadding` forces every child inside that inset,
 * including a horizontally scrolling card row that should run to the screen edge. With the inset owned
 * per section, [fullBleed] lets a collection's body reach the edge while its heading stays aligned with
 * every other section on the screen. The scroll container is left owning vertical insets only.
 *
 * @param kind Which sort of block this is; decides the gap above it. See [TeddSectionKind].
 * @param modifier Modifier applied to the section's root, outside its own gap and inset.
 * @param title The section's heading; omitted when null, which is normal for [TeddSectionKind.Status]
 * and [TeddSectionKind.Masthead] blocks that draw their own header content.
 * @param description A quieter second line under [title], for a count or a one-line explanation.
 * Ignored when [title] is null, since there would be nothing for it to qualify.
 * @param action A trailing control on the heading row — "View all" and the like. Ignored when [title] is
 * null, because without a heading there is no row to place it on.
 * @param fullBleed Whether [content] ignores the horizontal screen inset. True only for a
 * [TeddSectionKind.Collection] whose body scrolls horizontally and supplies its own leading inset; the
 * heading keeps the screen inset either way, so headings stay aligned down the screen.
 * @param verticalArrangement The arrangement applied to items inside the section's body column. Null uses
 * the theme's item-gap token as spacing — the right default for a list of document rows or settings
 * entries. Pass [Arrangement.Top] when the body holds a single child such as a grid or horizontal pager,
 * where inter-item gap has no meaning. Pass a different `spacedBy` token when intentionally tighter packing
 * is wanted. The gap above this section is not controlled here — that is owned by [TeddSectionKind] to keep
 * hierarchy visible across the screen.
 * @param content The section's body.
 */
@Composable
fun TeddSection(
    kind: TeddSectionKind,
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
    fullBleed: Boolean = false,
    verticalArrangement: Arrangement.Vertical? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val resolvedArrangement = verticalArrangement ?: Arrangement.spacedBy(spacing.itemGap)

    Column(modifier = modifier.fillMaxWidth().padding(top = kind.topGap(spacing))) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TeddText(text = title, style = typography.titleMedium)
                    if (description != null) {
                        TeddText(
                            text = description,
                            style = typography.settingDescription,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                if (action != null) {
                    action()
                }
            }
        }

        Column(
            modifier = if (fullBleed) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.fillMaxWidth().padding(horizontal = spacing.screenPadding)
            }.padding(top = if (title != null) spacing.sectionHeaderGap else spacing.none),
            verticalArrangement = resolvedArrangement,
            content = content,
        )
    }
}

/** Compose preview rendering a collection section with a heading, a description, and a trailing action. */
@Preview
@Composable
private fun TeddSectionPreview() {
    TeddPreviewSurface {
        TeddSection(
            kind = TeddSectionKind.Collection,
            title = "Recent reading",
            description = "Twelve documents",
            action = { TeddButton(text = "View all", onClick = {}, emphasis = TeddButtonEmphasis.Text) },
        ) {
            TeddText(text = "A document row")
            TeddText(text = "Another document row")
        }
    }
}

/**
 * Preview of a single-child section — a grid or pager that fills the body — where the default
 * item-gap arrangement is suppressed with [Arrangement.Top].
 */
@Preview
@Composable
private fun TeddSectionSingleChildPreview() {
    TeddPreviewSurface {
        TeddSection(
            kind = TeddSectionKind.Collection,
            title = "Pinned",
            verticalArrangement = Arrangement.Top,
        ) {
            TeddText(text = "A full-width grid or pager lives here")
        }
    }
}
