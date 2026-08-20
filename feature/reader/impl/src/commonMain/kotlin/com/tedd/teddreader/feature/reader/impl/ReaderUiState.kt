package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isImagePageFormat
import com.tedd.teddreader.core.common.model.isVisualPageFormat
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class ReaderPageUi(
    val page: Int = 0,
    val text: String = "",
    val isPdf: Boolean = false,
    val documentUri: String? = null,
    val textRange: TextRange? = null,
    val blocks: ImmutableList<ReaderBlock> = persistentListOf(),
    val embeddedImages: ImmutableMap<String, ByteArray> = persistentMapOf(),
    val failedEmbeddedImageHrefs: ImmutableSet<String> = persistentSetOf(),
    val chapterTitle: String? = null,
    // True when this page is the last page of its section — the honest, by-construction signal
    // EpubPageSurface centres a short page on, in place of how much of the sheet it rendered to fill
    // (see that composable's own doc for why the rendered-height signal broke on an estimated page).
    val isSectionTail: Boolean = false,
)

@Immutable
data class ReaderUiState(
    val documentTitle: String = "Reader",
    val documentUri: String? = null,
    val documentFormat: DocumentFormat = DocumentFormat.UNKNOWN,
    val pageText: String = "",
    val pageIndex: PageIndex = PageIndex(current = 0, total = 0),
    val previousPage: ReaderPageUi? = null,
    val currentPage: ReaderPageUi = ReaderPageUi(),
    val nextPage: ReaderPageUi? = null,
    val pageSlots: ImmutableList<ReaderPageUi> = persistentListOf(),
    val documentPages: ImmutableList<ReaderPageUi> = persistentListOf(),
    val style: ReaderStyle = ReaderStyle(),
    val isControlsVisible: Boolean = true,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val activeSheet: ReaderOptionSheet? = null,
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val outlineHeading: String? = null,
    val outlineItems: ImmutableList<ReaderOutlineItem> = persistentListOf(),
    val brightnessOverlayAlpha: Float = 0f,
    val pdfZoom: Float = 1f,
    val pdfRotationDegrees: Float = 0f,
    val keepScreenOn: Boolean = false,
    val fullscreen: Boolean = false,
    val showProgress: Boolean = true,
    val isPdfMode: Boolean = false,
    val visualPageImages: ImmutableMap<Int, ByteArray> = persistentMapOf(),
    val failedVisualPages: ImmutableSet<Int> = persistentSetOf(),
    val isFavorite: Boolean = false,
    val isCurrentPageSaved: Boolean = false,
    val isSavingSettings: Boolean = false,
    // False while a progressively-imported EPUB is still being parsed and measured in the background
    // (see ReaderViewModel.continueImportIfIncomplete), or while the current style/line-height/typeface
    // has never been measured for this book before and is still being laid out one section at a time
    // (see continuePaginationIfIncomplete) — total, then, is "pages known so far," not the whole book,
    // so the UI must say so rather than show a total that keeps growing silently. True once both are
    // done, including for a document that needed neither.
    val isPaginationComplete: Boolean = true,
) {
    val isVisualMode: Boolean get() = isPdfMode || documentFormat.isVisualPageFormat()
    val isImageMode: Boolean get() = documentFormat.isImagePageFormat()
}
