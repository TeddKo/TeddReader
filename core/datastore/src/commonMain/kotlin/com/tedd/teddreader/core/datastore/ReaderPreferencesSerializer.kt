package com.tedd.teddreader.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
import com.tedd.teddreader.core.common.model.PageAnimation
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
            else -> json.decodeFromString<ReaderPreferences>(raw).let { preferences ->
                if (preferences.pageAnimation == PageAnimation.SHEET_FLIP) {
                    preferences.copy(pageAnimation = PageAnimation.SLIDE)
                } else {
                    preferences
                }
            }
        }
    } catch (exception: SerializationException) {
        throw CorruptionException("Cannot read reader preferences.", exception)
    }

    override suspend fun writeTo(t: ReaderPreferences, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(t))
    }
}
