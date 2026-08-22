package com.tedd.teddreader.core.datastore

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlinx.serialization.Serializable

/**
 * Reading preferences exactly as they sit in the JSON file on disk.
 *
 * A storage type of its own, not the domain's `ReaderSettings`, so the two can differ without a migration:
 * a field renamed in the domain does not rename a key readers already have on disk, and a key dropped from
 * disk does not have to stay in the domain. `ignoreUnknownKeys` on the serializer makes the other direction
 * safe too — a file written by a newer build still reads here.
 *
 * The defaults are what a fresh install starts with and what a missing key falls back to.
 *
 * @property style the type and page colours the reader draws with.
 * @property pageTurnMode which way a page turn goes; a stored `CONTINUOUS` is normalised away on read.
 * @property pageAnimation which pager animates a turn; the legacy values are normalised away on read.
 * @property autoScrollConfig auto-scroll's switch, unit and speed, with the speed clamped on read.
 * @property appLanguage the language the app's own strings use.
 */
@Serializable
data class ReaderPreferences(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
)
