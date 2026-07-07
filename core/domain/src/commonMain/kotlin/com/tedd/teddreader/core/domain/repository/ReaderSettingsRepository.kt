package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlinx.coroutines.flow.Flow

data class ReaderSettings(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
)

interface ReaderSettingsRepository {
    val settings: Flow<ReaderSettings>
    suspend fun updateStyle(style: ReaderStyle)
    suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode)
    suspend fun updatePageAnimation(pageAnimation: PageAnimation)
    suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig)
}
