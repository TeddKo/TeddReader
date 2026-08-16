package com.tedd.teddreader.core.common.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReaderImageSizeTest {
    private fun imageBlock(
        aspectRatio: Float? = null,
        naturalWidthPx: Int? = null,
        widthPercent: Float? = null,
        widthEm: Float? = null,
    ) = ReaderBlock(
        kind = ReaderBlockKind.IMAGE,
        range = TextRange(0, 1),
        imageHref = "Images/plate.jpg",
        imageAspectRatio = aspectRatio,
        imageNaturalWidthPx = naturalWidthPx,
        imageWidthPercent = widthPercent,
        imageWidthEm = widthEm,
    )

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    @Test
    fun stylesheetPercentSizesTheImageAgainstTheColumn() {
        // .img_full{width:90%} on a 20em column.
        val size = imageBlock(aspectRatio = 0.663f, naturalWidthPx = 630, widthPercent = 0.9f)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(18f, size.widthEm)
        assertClose(18f / 0.663f, size.heightEm)
    }

    @Test
    fun aHairlineRuleKeepsItsOwnHeightInsteadOfFillingTheColumn() {
        // old_line1.png is 640x25 and its class declares no width, so it keeps its shape.
        val size = imageBlock(aspectRatio = 640f / 25f, naturalWidthPx = 640)
            .readerImageSize(columnWidthEm = 17f, maxHeightEm = 30f, emInPx = 22f)

        assertClose(17f, size.widthEm)
        assertTrue(size.heightEm < 1f, "a 25.6:1 rule must stay under one line, was ${size.heightEm}")
    }

    @Test
    fun aSmallPictureIsNotBlownUpPastItsNaturalSize() {
        // 110 CSS px at 22px per em is 5em, well under the column, so it stays 5em.
        val size = imageBlock(aspectRatio = 1f, naturalWidthPx = 110)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(5f, size.widthEm)
        assertClose(5f, size.heightEm)
    }

    @Test
    fun aTallPlateIsScaledDownToThePageAndKeepsItsProportions() {
        // A portrait plate twice as tall as it is wide cannot fill the column on a short page. It is
        // capped at 95% of the page, the `max-height: 95vh` Readium's stylesheet puts on any image,
        // which leaves the line box holding it somewhere to sit.
        val size = imageBlock(aspectRatio = 0.5f, naturalWidthPx = 2000)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 24f, emInPx = 22f)

        assertClose(22.8f, size.heightEm)
        assertClose(11.4f, size.widthEm)
        assertClose(0.5f, size.widthEm / size.heightEm)
    }

    @Test
    fun anImageNeverClaimsTheWholePage() {
        // No aspect ratio is known, so the box falls back to the page — but still not all of it.
        val size = imageBlock()
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 30f, emInPx = 22f)

        assertTrue(size.heightEm < 30f, "an image must leave room on the page, was ${size.heightEm}")
        assertClose(28.5f, size.heightEm)
    }

    @Test
    fun emWidthFromTheStylesheetIsUsedVerbatim() {
        val size = imageBlock(aspectRatio = 1f, naturalWidthPx = 800, widthEm = 2.5f)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(2.5f, size.widthEm)
    }

    @Test
    fun aSeparatorIsOneRuleWide() {
        val size = ReaderBlock(kind = ReaderBlockKind.SEPARATOR, range = TextRange(0, 1))
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertEquals(20f, size.widthEm)
        assertClose(1.25f, size.heightEm)
    }
}
