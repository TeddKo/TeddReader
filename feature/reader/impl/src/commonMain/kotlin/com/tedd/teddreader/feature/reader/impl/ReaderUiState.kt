package com.tedd.teddreader.feature.reader.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle

@Immutable
data class ReaderPageUi(
    val page: Int = 0,
    val text: String = "",
    val isPdf: Boolean = false,
    val documentUri: String? = null,
)

@Immutable
data class ReaderUiState(
    val documentTitle: String = "Reader",
    val documentUri: String? = null,
    val pageText: String = "",
    val pageIndex: PageIndex = PageIndex(current = 0, total = 0),
    val previousPage: ReaderPageUi? = null,
    val currentPage: ReaderPageUi = ReaderPageUi(),
    val nextPage: ReaderPageUi? = null,
    val pageSlots: List<ReaderPageUi> = emptyList(),
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
    val isFavorite: Boolean = false,
    val isCurrentPageSaved: Boolean = false,
    val isSavingSettings: Boolean = false,
)
