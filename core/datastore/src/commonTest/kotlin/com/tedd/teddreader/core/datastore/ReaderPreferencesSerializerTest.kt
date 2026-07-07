package com.tedd.teddreader.core.datastore

import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.sepiaReaderStyle
import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderPreferencesSerializerTest {
    @Test
    fun preferencesRoundTripThroughJson() = runTest {
        val preferences = ReaderPreferences(
            style = sepiaReaderStyle().copy(fontSizeSp = 24f),
            pageTurnMode = PageTurnMode.VERTICAL,
        )
        val buffer = Buffer()

        ReaderPreferencesSerializer.writeTo(preferences, buffer)

        assertEquals(preferences, ReaderPreferencesSerializer.readFrom(buffer))
    }

    @Test
    fun blankJsonReturnsDefaultPreferences() = runTest {
        assertEquals(
            ReaderPreferences(),
            ReaderPreferencesSerializer.readFrom(Buffer()),
        )
    }
}
