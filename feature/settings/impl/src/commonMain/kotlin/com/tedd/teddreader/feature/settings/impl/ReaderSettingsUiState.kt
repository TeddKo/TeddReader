package com.tedd.teddreader.feature.settings.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle

@Immutable
data class ReaderSettingsUiState(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val isLoading: Boolean = true,
)
