package com.tedd.teddreader.core.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeddUiComponentTest {

    @Test
    fun teddButtonEmphasisValuesAreExhaustive() {
        val emphases = TeddButtonEmphasis.entries
        assertEquals(4, emphases.size)
        assertTrue(emphases.contains(TeddButtonEmphasis.Primary))
        assertTrue(emphases.contains(TeddButtonEmphasis.Secondary))
        assertTrue(emphases.contains(TeddButtonEmphasis.Text))
        assertTrue(emphases.contains(TeddButtonEmphasis.Destructive))
    }
}
