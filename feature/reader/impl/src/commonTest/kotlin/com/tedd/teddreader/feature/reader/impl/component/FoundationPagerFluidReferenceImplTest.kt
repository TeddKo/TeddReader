package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoundationPagerFluidReferenceImplTest {
    @Test
    fun `tap zones preserve page navigation and controls semantics`() {
        assertEquals(
            FoundationReferenceFluidTapAction.Previous,
            foundationReferenceFluidTapAction(primary = 24f, extent = 100, currentPage = 1, pageCount = 3),
        )
        assertEquals(
            FoundationReferenceFluidTapAction.ToggleControls,
            foundationReferenceFluidTapAction(primary = 25f, extent = 100, currentPage = 1, pageCount = 3),
        )
        assertEquals(
            FoundationReferenceFluidTapAction.ToggleControls,
            foundationReferenceFluidTapAction(primary = 75f, extent = 100, currentPage = 1, pageCount = 3),
        )
        assertEquals(
            FoundationReferenceFluidTapAction.Next,
            foundationReferenceFluidTapAction(primary = 76f, extent = 100, currentPage = 1, pageCount = 3),
        )
        assertNull(foundationReferenceFluidTapAction(primary = 0f, extent = 100, currentPage = 0, pageCount = 3))
        assertNull(foundationReferenceFluidTapAction(primary = 99f, extent = 100, currentPage = 2, pageCount = 3))
        assertNull(
            foundationReferenceFluidTapAction(
                primary = 99f,
                extent = 100,
                currentPage = 2,
                pageCount = 4,
                pageStep = 2,
            ),
        )
    }

    @Test
    fun `drag direction starts only beyond the twenty pixel gate`() {
        assertEquals(0f, foundationReferenceFluidDragDirection(20f))
        assertEquals(1f, foundationReferenceFluidDragDirection(20.1f))
        assertEquals(-1f, foundationReferenceFluidDragDirection(-20.1f))
    }

    @Test
    fun `target page follows reference direction without wrapping`() {
        assertEquals(0, foundationReferenceFluidTargetPage(1, 1f, 3))
        assertEquals(2, foundationReferenceFluidTargetPage(1, -1f, 3))
        assertEquals(1, foundationReferenceFluidTargetPage(3, 1f, 10, pageStep = 2))
        assertEquals(5, foundationReferenceFluidTargetPage(3, -1f, 10, pageStep = 2))
        assertEquals(0, foundationReferenceFluidTargetPage(1, 1f, 10, pageStep = 2))
        assertEquals(4, foundationReferenceFluidTargetPage(2, -1f, 5, pageStep = 2))
        assertNull(foundationReferenceFluidTargetPage(2, -1f, 4, pageStep = 2))
        assertNull(foundationReferenceFluidTargetPage(0, 1f, 3))
        assertNull(foundationReferenceFluidTargetPage(2, -1f, 3))
    }

    @Test
    fun `completion uses inclusive twenty percent of the full extent`() {
        assertFalse(foundationReferenceFluidShouldComplete(19.9f, 100f))
        assertTrue(foundationReferenceFluidShouldComplete(20f, 100f))
        assertTrue(foundationReferenceFluidShouldComplete(-20f, 100f))
        assertFalse(foundationReferenceFluidShouldComplete(20f, 0f))
    }

    @Test
    fun `vertical points map progress from the corresponding screen edge`() {
        val size = Size(100f, 200f)
        val topStart = foundationReferenceFluidVerticalOffset(
            FoundationReferenceFluidPoint(x = 0.0, y = 0.25),
            size,
            FoundationReferenceFluidSide.TOP,
        )
        val bottomStart = foundationReferenceFluidVerticalOffset(
            FoundationReferenceFluidPoint(x = 0.0, y = 0.25),
            size,
            FoundationReferenceFluidSide.BOTTOM,
        )
        val topHalf = foundationReferenceFluidVerticalOffset(
            FoundationReferenceFluidPoint(x = 0.5, y = 0.5),
            size,
            FoundationReferenceFluidSide.TOP,
        )
        val bottomHalf = foundationReferenceFluidVerticalOffset(
            FoundationReferenceFluidPoint(x = 0.5, y = 0.5),
            size,
            FoundationReferenceFluidSide.BOTTOM,
        )

        assertEquals(Offset(25f, 0f), topStart)
        assertEquals(Offset(25f, 200f), bottomStart)
        assertEquals(Offset(50f, 100f), topHalf)
        assertEquals(Offset(50f, 100f), bottomHalf)
    }

    @Test
    fun `twenty five point physics pulls the touched point first`() {
        val edge = FoundationReferenceFluidEdge(count = 25)
        edge.applyTouchOffset(Offset(50f, 50f), Size(100f, 100f))

        edge.tick(1_000L)

        assertTrue(edge.points[12].x > 0.0)
        assertEquals(0.0, edge.points.first().x)
        assertEquals(0.0, edge.points.last().x)
    }

    @Test
    fun `physics does not clamp point displacement`() {
        val edge = FoundationReferenceFluidEdge(count = 2).apply {
            points[0].x = 1.25
            edgeTension = 0.0
            farEdgeTension = 0.0
            pointTension = 0.0
        }

        edge.tick(1_000L)

        assertEquals(1.25, edge.points[0].x)
    }
}
