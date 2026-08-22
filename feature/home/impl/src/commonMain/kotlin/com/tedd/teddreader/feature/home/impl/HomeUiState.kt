package com.tedd.teddreader.feature.home.impl

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.ui.system.DisplayFold
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList

/**
 * The home/library screen's full snapshot, as
 * [HomeViewModel][com.tedd.teddreader.feature.home.impl.HomeViewModel] publishes it and
 * [HomeScreen][com.tedd.teddreader.feature.home.impl.HomeScreen] renders it: the favorites and
 * recent shelves, the library grid and its folders, and the sort/filter/loading/error state the
 * screen reflects back to the user.
 *
 * [libraryDocuments] and [documentCoverImages] are both already narrowed to [formatFilter] and
 * ordered by [sort]. [libraryFolders] is deliberately built from every document regardless of
 * that filter, so a folder does not vanish just because none of the documents currently shown
 * happen to be filed under it. [hasDocuments] asks a different question than an empty
 * [libraryDocuments] does: it stays true as long as the library holds anything at all, which is
 * what lets the screen tell "nothing has ever been imported" apart from "the current filter
 * matches nothing."
 *
 * @property favoriteDocuments Bookmarked documents, filtered and sorted the same way as
 *   [libraryDocuments].
 * @property recentDocuments The newest 20 non-bookmarked documents matching [formatFilter],
 *   ordered by when each was last opened — this ordering is fixed and does not follow [sort].
 * @property libraryDocuments Every document that matches [formatFilter], in [sort] order.
 * @property libraryFolders The library's folders, computed from the whole, unfiltered document
 *   list so a folder stays visible even while [formatFilter] hides all of its contents.
 * @property documentCoverImages Decoded cover bytes keyed by document id, held only for
 *   documents that survive [formatFilter] — a cover already fetched for a now-hidden document is
 *   dropped rather than carried into view.
 * @property hasDocuments Whether the library holds any document at all, independent of
 *   [formatFilter]; distinguishes an empty library from a filter that matches nothing.
 * @property sort The library's current sort order, echoed back so the screen can show which
 *   option is selected.
 * @property formatFilter The library's current format restriction, echoed back for the same
 *   reason as [sort].
 * @property isLoading True until the document list has been read at least once.
 * @property errorMessage Non-null when loading the library, or a bookmark, delete, or folder
 *   write, most recently failed.
 * @property unsupportedFormatMessage A message about an import elsewhere in the app that could
 *   not be handled, such as an unsupported file.
 *   [HomeViewModel][com.tedd.teddreader.feature.home.impl.HomeViewModel] itself always emits this
 *   as null; `HomeRouteScreen` fills it in from that import's own result before handing the
 *   state to the screen.
 */
@Immutable
data class HomeUiState(
    val favoriteDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val recentDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryFolders: ImmutableList<LibraryFolder> = persistentListOf(),
    val documentCoverImages: ImmutableMap<String, ByteArray> = persistentMapOf(),
    val hasDocuments: Boolean = false,
    val sort: HomeSort = HomeSort.Recent,
    val formatFilter: HomeFormatFilter = HomeFormatFilter.All,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val unsupportedFormatMessage: String? = null,
)

/**
 * One folder shown in the library's Folders view: the identity and count a folder tile needs
 * without loading every document filed under it.
 *
 * Built by [buildLibraryFolders], one entry per distinct folder id among the library's documents
 * — see that function for why a document only contributes here once its folder id and folder
 * name are both known.
 *
 * @property id The folder's identity, matching `DocumentMetadata.folderId` on its documents.
 * @property name The folder's display name.
 * @property documentCount How many documents are filed under this folder, used together with a
 *   preview's own size to compute how many remain unshown (see
 *   [libraryFolderRemainingDocumentCount]).
 */
@Immutable
data class LibraryFolder(
    val id: String,
    val name: String,
    val documentCount: Int,
)

/**
 * How the library list and its favorites are ordered — by recency, alphabetically by title, or
 * by format then title — as chosen via [HomeUiState.sort] and applied wherever
 * `HomeViewModel` builds [HomeUiState.libraryDocuments] or [HomeUiState.favoriteDocuments].
 */
enum class HomeSort {
    Recent,
    Title,
    Format,
}

/**
 * Restricts the library and its cover cache to a single `DocumentFormat`, or lifts the
 * restriction with [All]. Chosen via [HomeUiState.formatFilter] and applied to every document
 * list in [HomeUiState] except [HomeUiState.libraryFolders], which is built from the whole,
 * unfiltered document list on purpose.
 */
enum class HomeFormatFilter {
    All,
    Txt,
    Pdf,
    Epub,
    Comic,
    Image,
}

/**
 * Which shelf a [HomeDocumentActionTarget] belongs to. A document normally appears in
 * [HomeUiState.libraryDocuments] plus whichever of [HomeUiState.favoriteDocuments] or
 * [HomeUiState.recentDocuments] it also qualifies for, under the same document id — this tag is
 * what tells two rows sharing that id apart, so opening one card's overflow menu does not also
 * appear open on its duplicate on the other shelf.
 */
internal enum class HomeDocumentSection {
    Favorites,
    Recent,
    Library,
}

/**
 * Identifies exactly one document row's overflow menu as open: which shelf it is on plus the
 * document itself, since the same document id can be showing on more than one shelf at once (see
 * [HomeDocumentSection]).
 *
 * @property section Which shelf the open menu belongs to.
 * @property documentId The document whose menu is open.
 */
internal data class HomeDocumentActionTarget(
    val section: HomeDocumentSection,
    val documentId: String,
)

/**
 * Whether the library preview shows loose documents or their folders. Toggled by the All/Folders
 * chip on the home screen's library section and mirrored by the dedicated library screen.
 */
enum class LibraryCollectionMode {
    All,
    Folders,
}

/**
 * How many library items the home screen's preview grid may show before "show all" takes over,
 * chosen from how much screen the device actually offers: an expanded window, a tablet-width
 * shortest side, or a foldable with a separating fold each unlock the wider limit; a plain phone
 * gets the narrower one. Kept separate from the screen-size math itself ([libraryPreviewLimit])
 * so the rule can be exercised directly from a test without measuring a real window.
 *
 * @param isExpanded Whether the window size class is expanded.
 * @param isTablet Whether the shortest side is at least tablet width.
 * @param hasSeparatingFold Whether the device reports a fold that separates the screen into two
 *   panes.
 * @return 8 when any of the three widen the layout, 4 otherwise.
 */
internal fun homeLibraryPreviewLimit(
    isExpanded: Boolean,
    isTablet: Boolean,
    hasSeparatingFold: Boolean,
): Int = if (isExpanded || isTablet || hasSeparatingFold) 8 else 4

/**
 * [homeLibraryPreviewLimit] as the home screen actually calls it: derives `isTablet` from the
 * measured shortest side and `hasSeparatingFold` from the device's own fold reporting, leaving
 * `isExpanded` always false since the home screen has no expanded-window case of its own.
 *
 * @param shortestSide The window's shortest side, as measured by the caller.
 * @param displayFold The device's current fold state, or null on a device that does not fold.
 */
internal fun libraryPreviewLimit(
    shortestSide: Dp,
    displayFold: DisplayFold?,
): Int = homeLibraryPreviewLimit(
    isExpanded = false,
    isTablet = shortestSide >= 600.dp,
    hasSeparatingFold = displayFold?.isVertical == true && displayFold.isSeparating,
)

/**
 * The first [previewLimit] documents to show in the home screen's "All" library preview, in the
 * order the caller already sorted them — this only trims the list to size, it does not reorder
 * it.
 *
 * @param documents The already-ordered documents to preview.
 * @param previewLimit How many to keep; see [homeLibraryPreviewLimit].
 */
internal fun homeLibraryPreviewDocuments(
    documents: List<DocumentMetadata>,
    previewLimit: Int,
): ImmutableList<DocumentMetadata> = documents.take(previewLimit).toImmutableList()

/**
 * Groups [items] into fixed-width rows of [columns] for a preview grid, so the grid always lays
 * out a whole number of same-width cells per row instead of letting a genuinely short last row
 * stretch its few remaining items across the row's full width.
 *
 * A short final row is padded with `null` up to [columns] rather than left short: the caller
 * renders a `null` slot as an empty spacer holding its column's weight, so the real items in
 * that row keep the same width and alignment as every full row above it. A caller reading only
 * this function's return type cannot tell that shape apart from a row that simply had fewer
 * items — every row here is exactly [columns] long, and a `null` entry means "no item, hold the
 * space."
 *
 * @param items The items to lay out, in the order they should appear.
 * @param columns How wide each row is; must be positive.
 * @return Rows of exactly [columns] entries each, with `null` standing in for a slot the last row
 *   did not have enough items to fill.
 * @throws IllegalArgumentException if [columns] is not positive.
 */
internal fun <T : Any> homeLibraryGridRows(
    items: List<T>,
    columns: Int,
): List<List<T?>> {
    require(columns > 0) { "columns must be positive." }
    return items.chunked(columns).map { row ->
        row.map<T, T?> { it } + List(columns - row.size) { null }
    }
}

/**
 * The first [previewLimit] documents filed under [folderId], in [documents]' own order — the
 * thumbnails a folder's cover tile shows without loading the folder's full contents.
 *
 * @param documents The library's documents to search, in the order their thumbnails should
 *   appear if chosen.
 * @param folderId The folder to collect documents for.
 * @param previewLimit How many to keep.
 */
internal fun libraryFolderPreviewDocuments(
    documents: List<DocumentMetadata>,
    folderId: String,
    previewLimit: Int,
): ImmutableList<DocumentMetadata> =
    documents.filter { it.folderId == folderId }.take(previewLimit).toImmutableList()

/**
 * How many of a folder's documents are not shown in its preview, for the "+N more" label a
 * folder cover tile shows alongside its thumbnails. Floored at zero rather than left negative,
 * since [previewCount] can already equal [totalCount] once every document in a small folder
 * fits in the preview.
 *
 * @param totalCount How many documents the folder actually holds.
 * @param previewCount How many of them the preview is already showing.
 * @return [totalCount] minus [previewCount], never negative.
 */
internal fun libraryFolderRemainingDocumentCount(
    totalCount: Int,
    previewCount: Int,
): Int = (totalCount - previewCount).coerceAtLeast(0)

/**
 * The library's folders, one [LibraryFolder] per distinct folder id among [documents], named and
 * counted from the documents filed under it, sorted for a stable display order.
 *
 * A document only contributes a folder here once both its folder id and folder name are
 * present. `DocumentMetadata` itself requires the two to be both-null or both-non-null (see its
 * own `@throws`), so this pair cannot actually disagree on any real instance; checking both
 * anyway states the real intent — a folder needs an id *and* a name to be worth showing — without
 * this function silently depending on that invariant holding elsewhere to stay correct. The
 * `firstOrNull()?.folderName` read a few lines down is the same caution applied once more: it
 * still skips a folder id whose group somehow yields no name, rather than assuming the invariant
 * makes that impossible.
 *
 * @param documents The documents to derive folders from; a document with no folder does not
 *   contribute one.
 * @return One [LibraryFolder] per distinct folder id, ordered by name (case-insensitively).
 */
internal fun buildLibraryFolders(documents: List<DocumentMetadata>): List<LibraryFolder> =
    documents
        .filter { it.folderId != null && it.folderName != null }
        .groupBy { it.folderId!! }
        .mapNotNull { (folderId, folderDocuments) ->
            val folderName = folderDocuments.firstOrNull()?.folderName ?: return@mapNotNull null
            LibraryFolder(
                id = folderId,
                name = folderName,
                documentCount = folderDocuments.size,
            )
        }
        .sortedBy { it.name.lowercase() }
