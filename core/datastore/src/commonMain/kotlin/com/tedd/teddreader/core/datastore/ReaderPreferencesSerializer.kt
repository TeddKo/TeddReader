package com.tedd.teddreader.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource

/**
 * Reads and writes [ReaderPreferences] as JSON, and the one place stored settings are made sane before
 * anything else sees them.
 *
 * Okio-based rather than platform file APIs because this runs on both Android and iOS from common code.
 * Note the shape of [readFrom] and [writeTo]: they take Okio's own source and sink, and must not be
 * rewritten to use `use {}` — that extension compiles on Android and fails on Kotlin/Native.
 *
 * Normalising on both read and write is deliberate. Legacy enum values written by older builds are mapped
 * to what replaced them, and an out-of-range speed is clamped, so nothing downstream has to know that a
 * stored value might name a pager that no longer exists.
 *
 * An unparseable file raises `CorruptionException`, which is DataStore's signal to replace it with
 * [defaultValue] rather than crash: settings are worth losing before a launch is.
 */
object ReaderPreferencesSerializer : OkioSerializer<ReaderPreferences> {
    /**
     * The codec behind [readFrom] and [writeTo]. `ignoreUnknownKeys = true` so a file written by a
     * newer build — carrying a preference this build does not know about — still decodes instead of
     * throwing and losing every other stored setting. `encodeDefaults = true` so every field is
     * written out explicitly rather than omitted when it happens to match its Kotlin default,
     * making a stored file a complete snapshot of [ReaderPreferences] that reads the same way
     * regardless of what this class's own defaults are at the time it is read. Neither flag can be
     * flipped without checking that every existing stored file still reads the same afterward.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** What a missing or corrupt file reads as: every preference at its default. */
    override val defaultValue: ReaderPreferences = ReaderPreferences()

    /**
     * @param source the file's bytes, or an empty source when the file does not exist yet.
     * @return the stored preferences, normalised; [defaultValue] for an empty file.
     * @throws CorruptionException when the JSON cannot be parsed, which tells DataStore to fall back to
     * [defaultValue] instead of failing the read.
     */
    override suspend fun readFrom(source: BufferedSource): ReaderPreferences = try {
        val raw = source.readUtf8()
        when {
            raw.isBlank() -> defaultValue
            else -> normalize(json.decodeFromString<ReaderPreferences>(raw))
        }
    } catch (exception: SerializationException) {
        throw CorruptionException("Cannot read reader preferences.", exception)
    }

    /**
     * @param t the preferences to store; normalised first, so a legacy value read from an old file is not
     * written straight back out.
     * @param sink where the JSON goes.
     */
    override suspend fun writeTo(t: ReaderPreferences, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(normalize(t)))
    }

    /**
     * Replaces values that no longer mean anything with the ones that do.
     *
     * @param preferences preferences as read from, or about to be written to, disk.
     * @return the same preferences with `CONTINUOUS` read as vertical, `BOOK_CURL` as the curl pager,
     * `SHEET_FLIP` as slide, and the auto-scroll speed clamped into range.
     */
    private fun normalize(preferences: ReaderPreferences): ReaderPreferences = preferences.copy(
        pageTurnMode = when (preferences.pageTurnMode) {
            PageTurnMode.CONTINUOUS -> PageTurnMode.VERTICAL
            else -> preferences.pageTurnMode
        },
        pageAnimation = when (preferences.pageAnimation) {
            PageAnimation.BOOK_CURL -> PageAnimation.CURL_PAGER
            PageAnimation.SHEET_FLIP -> PageAnimation.SLIDE
            else -> preferences.pageAnimation
        },
        autoScrollConfig = preferences.autoScrollConfig.copy(
            speed = AutoScrollConfig.clampSpeed(preferences.autoScrollConfig.speed),
        ),
    )
}
