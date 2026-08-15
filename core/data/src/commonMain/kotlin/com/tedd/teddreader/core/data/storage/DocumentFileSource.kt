package com.tedd.teddreader.core.data.storage

import com.tedd.teddreader.core.common.model.DocumentLocation

interface DocumentFileSource {
    suspend fun readBytes(location: DocumentLocation): ByteArray

    suspend fun materialize(location: DocumentLocation, bytes: ByteArray): DocumentLocation = location
}
