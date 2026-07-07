package com.tedd.teddreader.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.okio.OkioSerializer
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
        if (raw.isBlank()) defaultValue else json.decodeFromString(raw)
    } catch (exception: SerializationException) {
        throw CorruptionException("Cannot read reader preferences.", exception)
    }

    override suspend fun writeTo(t: ReaderPreferences, sink: BufferedSink) {
        sink.writeUtf8(json.encodeToString(t))
    }
}
