package com.tedd.teddreader.core.domain.repository

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlinx.coroutines.flow.Flow

/**
 * Every reading preference as one value, so a screen renders from a single snapshot instead of
 * assembling one out of separate reads.
 *
 * The defaults live here rather than in the reader on purpose: a new preference gets its default in one
 * place, and every settings object already stored keeps deserializing without a migration.
 *
 * @property style the type and page colours the reader draws with — the only member whose
 * layout-affecting fields force pages to be measured again.
 * @property pageTurnMode which way a page turn goes, which also decides how a swipe and an edge tap are
 * read.
 * @property pageAnimation which pager implementation the reader uses for a turn.
 * @property autoScrollConfig whether auto-scroll is on, what it advances by, and how fast.
 * @property appLanguage the language the app's own strings are shown in, independent of any book.
 */
data class ReaderSettings(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
)

/**
 * Reading preferences, stored once for the app rather than per document.
 *
 * Exposed as a flow because a change made on the settings screen has to reach an open reader without
 * either screen knowing about the other: the reader collects [settings] and re-paginates when a
 * layout-affecting field moves.
 *
 * The writes are one per concern instead of a single `update(ReaderSettings)` so two screens editing
 * different preferences at the same time cannot overwrite each other's field with a stale copy of the
 * whole object.
 */
interface ReaderSettingsRepository {
    /** The current preferences and every later change, starting with whatever is stored now. */
    val settings: Flow<ReaderSettings>

    /**
     * Stores the reading style.
     *
     * @param style the new style. Changing its font size, line height or family invalidates measured
     * page layouts for the old type; changing only colours does not.
     */
    suspend fun updateStyle(style: ReaderStyle)

    /**
     * Stores the page-turn direction.
     *
     * @param pageTurnMode the new direction. `CONTINUOUS` is never written — see [PageTurnMode].
     */
    suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode)

    /**
     * Stores which pager animates a page turn.
     *
     * @param pageAnimation the new animation. The legacy values are never written — see [PageAnimation].
     */
    suspend fun updatePageAnimation(pageAnimation: PageAnimation)

    /**
     * Stores auto-scroll's switch, unit and speed together, since a speed means nothing without its unit.
     *
     * @param autoScrollConfig the new auto-scroll configuration, already clamped by
     * [AutoScrollConfig.clampSpeed] if it came from a slider.
     */
    suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig)

    /**
     * Stores the app language.
     *
     * @param appLanguage the new language; the composition root applies it on the next composition.
     */
    suspend fun updateAppLanguage(appLanguage: AppLanguage)
}
