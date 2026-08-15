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

@Immutable
data class ReaderPageUi(
    val page: Int = 0,
    val text: String = "",
    val isPdf: Boolean = false,
    val documentUri: String? = null,
    val textRange: TextRange? = null,
    val blocks: List<ReaderBlock> = emptyList(),
    val embeddedImages: Map<String, ByteArray> = emptyMap(),
    val failedEmbeddedImageHrefs: Set<String> = emptySet(),
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
    val pageSlots: List<ReaderPageUi> = emptyList(),
    val documentPages: List<ReaderPageUi> = emptyList(),
    val style: ReaderStyle = ReaderStyle(),
    val isControlsVisible: Boolean = true,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val activeSheet: ReaderOptionSheet? = null,
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val outlineItems: List<ReaderOutlineItem> = emptyList(),
    val brightnessOverlayAlpha: Float = 0f,
    val pdfZoom: Float = 1f,
    val pdfRotationDegrees: Float = 0f,
    val keepScreenOn: Boolean = false,
    val fullscreen: Boolean = false,
    val showProgress: Boolean = true,
    val isPdfMode: Boolean = false,
    val visualPageImages: Map<Int, ByteArray> = emptyMap(),
    val failedVisualPages: Set<Int> = emptySet(),
    val isFavorite: Boolean = false,
    val isCurrentPageSaved: Boolean = false,
    val isSavingSettings: Boolean = false,
) {
    val isVisualMode: Boolean get() = isPdfMode || documentFormat.isVisualPageFormat()
    val isImageMode: Boolean get() = documentFormat.isImagePageFormat()
}
