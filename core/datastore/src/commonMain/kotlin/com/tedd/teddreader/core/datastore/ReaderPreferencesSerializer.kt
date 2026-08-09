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

object ReaderPreferencesSerializer : OkioSerializer<ReaderPreferences> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: ReaderPreferences = ReaderPreferences()

    override suspend fun readFrom(source: BufferedSource): ReaderPreferences = try {
        val raw = source.readUtf8()
        when {
            raw.isBlank() -> defaultValue
            else -> normalize(json.decodeFromString<ReaderPreferences>(raw))
        }
    } catch (exception: SerializationException) {
        throw CorruptionException("Cannot read reader preferences.", exception)
    }

    override suspend fun writeTo(t: ReaderPreferences, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(normalize(t)))
    }

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
