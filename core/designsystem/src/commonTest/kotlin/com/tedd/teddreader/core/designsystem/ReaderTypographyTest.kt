package com.tedd.teddreader.core.designsystem

import androidx.compose.ui.text.font.FontWeight
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [ReaderStyle.readerTextStyle]'s one line that actually makes body text heavier or lighter:
 * `fontWeight = FontWeight(fontWeight)`. Both the page breaker and the page surface measure and draw
 * through the text style this function returns, so if that line were ever dropped or hardcoded back to
 * a fixed weight, every reader-weight setting would silently stop moving the drawn glyphs — this test
 * fails the moment that happens, by comparing the returned style's font weight against every weight the
 * setting actually offers.
 */
class ReaderTypographyTest {
    /** Each of the four weights the font-weight setting offers reaches the returned style unchanged. */
    @Test
    fun readerTextStyleCarriesEachOfferedFontWeight() {
        listOf(300, 400, 500, 600).forEach { weight ->
            val style = ReaderStyle(fontWeight = weight).readerTextStyle()

            assertEquals(FontWeight(weight), style.fontWeight)
        }
    }
}
