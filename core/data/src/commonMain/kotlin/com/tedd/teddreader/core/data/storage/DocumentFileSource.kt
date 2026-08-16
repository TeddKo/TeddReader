package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation
import okio.Path

interface DocumentFileSource {
    suspend fun readBytes(location: DocumentLocation): ByteArray

    suspend fun copyTo(location: DocumentLocation, destination: Path)

    suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation = location
}
