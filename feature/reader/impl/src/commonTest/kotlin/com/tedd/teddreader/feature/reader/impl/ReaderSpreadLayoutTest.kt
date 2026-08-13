package com.tedd.teddreader.feature.reader.impl

import androidx.compose.ui.unit.IntSize
import com.tedd.teddreader.core.ui.system.DisplayFold
import com.tedd.teddreader.feature.reader.impl.component.foundationReferenceLeafSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderSpreadLayoutTest {
    @Test
    fun separatingVerticalFoldOpensASpreadEvenBelowTheWidthBoundary() {
        assertEquals(2, readerPaneCount(widthDp = 520f, fold = bookSpine(startDp = 258f, endDp = 262f)))
    }

    @Test
    fun tabletAndPhoneKeepTheWidthRuleWhenThereIsNoFold() {
        assertEquals(2, readerPaneCount(widthDp = 840f, fold = null))
        assertEquals(1, readerPaneCount(widthDp = 420f, fold = null))
    }

    @Test
    fun flatFoldDoesNotForceASpread() {
        val flat = bookSpine(startDp = 258f, endDp = 262f).copy(isSeparating = false)

        assertEquals(1, readerPaneCount(widthDp = 520f, fold = flat))
    }

    @Test
    fun horizontalFoldIsNotASpine() {
        val tabletop = bookSpine(startDp = 300f, endDp = 320f).copy(isVertical = false)

        assertEquals(1, readerPaneCount(widthDp = 520f, fold = tabletop))
    }

    @Test
    fun spreadWeightPutsTheGutterOnAnOffCentreHinge() {
        val weight = readerSpreadLeftWeight(widthDp = 800f, fold = bookSpine(startDp = 300f, endDp = 320f))

        assertEquals(0.384f, weight, absoluteTolerance = 0.001f)
    }

    @Test
    fun spreadWeightStaysBalancedWithoutAFold() {
        assertEquals(0.5f, readerSpreadLeftWeight(widthDp = 800f, fold = null))
        assertEquals(0.5f, readerSpreadLeftWeight(widthDp = 0f, fold = bookSpine(startDp = 0f, endDp = 4f)))
    }

    @Test
    fun gutterClearsTheHingeButNeverShrinksBelowTheReadingGutter() {
        assertEquals(
            24f,
            readerSpreadGutterDp(fold = bookSpine(startDp = 288f, endDp = 312f), defaultGutterDp = 16f),
        )
        assertEquals(
            16f,
            readerSpreadGutterDp(fold = bookSpine(startDp = 298f, endDp = 302f), defaultGutterDp = 16f),
        )
        assertEquals(16f, readerSpreadGutterDp(fold = null, defaultGutterDp = 16f))
    }

    @Test
    fun leafIsTheOuterPageOfTheSpreadAndTheWholePageOtherwise() {
        val viewport = IntSize(1000, 800)

        assertEquals(
            IntSize(492, 800),
            foundationReferenceLeafSize(viewport, isSpread = true, gutterPx = 16f, leftWeight = 0.5f),
        )
        assertEquals(
            IntSize(590, 800),
            foundationReferenceLeafSize(viewport, isSpread = true, gutterPx = 16f, leftWeight = 0.4f),
        )
        assertEquals(
            viewport,
            foundationReferenceLeafSize(viewport, isSpread = false, gutterPx = 16f, leftWeight = 0.5f),
        )
    }

    private fun bookSpine(startDp: Float, endDp: Float) = DisplayFold(
        startDp = startDp,
        endDp = endDp,
        isVertical = true,
        isSeparating = true,
    )
}
