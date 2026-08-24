package com.tedd.teddreader.feature.settings.impl

import androidx.compose.runtime.Immutable
import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle

/**
 * The reader-settings screen's snapshot, as [ReaderSettingsViewModel] publishes it and
 * [ReaderSettingsRouteScreen] renders it. Every field below defaults to the same value
 * `ReaderSettings` itself defaults to, so a screen shown before the repository's first emission
 * renders the real defaults rather than a placeholder that would visibly jump once the actual
 * preferences load.
 *
 * @property style The type and page colours the reader draws with.
 * @property pageTurnMode Which way a page turn goes.
 * @property pageAnimation Which pager implementation animates a page turn.
 * @property autoScrollConfig Whether auto-scroll is on, what it advances by, and how fast.
 * @property appLanguage The language the app's own strings are shown in, independent of any book.
 * @property isLoading True until the repository's settings flow has emitted at least once;
 * false from then on, since a stored preference is never itself "loading" again after that.
 */
@Immutable
data class ReaderSettingsUiState(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val isLoading: Boolean = true,
)
