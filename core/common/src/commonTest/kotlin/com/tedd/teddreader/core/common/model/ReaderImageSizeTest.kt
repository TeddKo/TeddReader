package com.tedd.teddreader.core.common.model

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 이 리더가 여는 책에서 얻은 실제 수치를 기준으로 [readerImageSize]가 그림 크기를 정하는 방식을 경우별로 고정한다.
 *
 * 각 경우는 기기에서 한 번씩 잘못 동작했던 사례다. 가는 구분선이 두꺼운 띠로 표시되거나, 작은 로고가 포스터 크기로 늘어나거나, 세로로 긴 플레이트가 페이지 가장자리에서 잘렸다. 모든 기대값은 렌더링 쪽에서 사용하는 변환인 1em당 22 CSS 픽셀 기준의 em이므로 페이지에 실제 표시된 값과 비교할 수 있다.
 */
class ReaderImageSizeTest {
    /**
     * 경우에 필요한 측정값만 지정하고 나머지는 모두 알 수 없는 이미지 블록이다.
     */
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

    /**
     * 이 크기는 상수가 아니라 나눗셈에서 나오므로 허용 오차를 적용하는 Float 비교이다.
     */
    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    /**
     * 스타일시트 너비가 그림 자체 크기보다 우선한다. 20em 열에서 `.img_full{width:90%}`는 18em이다.
     */
    @Test
    fun stylesheetPercentSizesTheImageAgainstTheColumn() {
        val size = imageBlock(aspectRatio = 0.663f, naturalWidthPx = 630, widthPercent = 0.9f)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(18f, size.widthEm)
        assertClose(18f / 0.663f, size.heightEm)
    }

    /**
     * 너비가 지정되지 않은 그림은 열까지 늘어나지 않고 자체 비율을 유지한다. `old_line1.png`는 640x25이므로 25.6:1 구분선은 텍스트 한 줄보다 낮게 유지돼야 한다.
     */
    @Test
    fun aHairlineRuleKeepsItsOwnHeightInsteadOfFillingTheColumn() {
        val size = imageBlock(aspectRatio = 640f / 25f, naturalWidthPx = 640)
            .readerImageSize(columnWidthEm = 17f, maxHeightEm = 30f, emInPx = 22f)

        assertClose(17f, size.widthEm)
        assertTrue(size.heightEm < 1f, "a 25.6:1 rule must stay under one line, was ${size.heightEm}")
    }

    /**
     * `max-width`는 줄이기만 한다. 110 CSS px는 1em당 22px에서 5em이고 열보다 훨씬 작으므로 5em으로 유지된다.
     */
    @Test
    fun aSmallPictureIsNotBlownUpPastItsNaturalSize() {
        val size = imageBlock(aspectRatio = 1f, naturalWidthPx = 110)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(5f, size.widthEm)
        assertClose(5f, size.heightEm)
    }

    /**
     * 너비보다 높이가 두 배인 세로형 플레이트는 짧은 페이지에서 열을 채울 수 없으므로 페이지 상한을 적용한다. 페이지의 95%, 즉 Readium 스타일시트가 모든 이미지에 지정하는 `max-height: 95vh`는 이미지를 담는 줄 상자가 위치할 공간을 남긴다. 비율을 유지하도록 너비도 함께 줄인다.
     */
    @Test
    fun aTallPlateIsScaledDownToThePageAndKeepsItsProportions() {
        val size = imageBlock(aspectRatio = 0.5f, naturalWidthPx = 2000)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 24f, emInPx = 22f)

        assertClose(22.8f, size.heightEm)
        assertClose(11.4f, size.widthEm)
        assertClose(0.5f, size.widthEm / size.heightEm)
    }

    /**
     * 이 그림은 비율이 지정되지 않았으므로 상자는 페이지 전체가 아니라 정사각형이다. 페이지 전체를 할당하면 작은 삽화가 화면 가득한 빈 공간에 고립되고 주변 텍스트가 페이지 밖으로 밀려난다. 그림 자체는 어차피 그릴 때 실제 형태를 유지한다.
     */
    @Test
    fun anImageWithUnreadableProportionsIsSquaredOffRatherThanGivenThePage() {
        val size = imageBlock()
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 30f, emInPx = 22f)

        assertTrue(size.heightEm < 30f, "an image must leave room on the page, was ${size.heightEm}")
        assertClose(20f, size.widthEm)
        assertClose(20f, size.heightEm)
    }

    /**
     * 정사각형 대체값도 페이지에 제한된다. 9.5em은 10em 페이지의 95%로, 측정된 상자와 같은 상한이다.
     */
    @Test
    fun anUnmeasurableImageStillShrinksToFitAShortPage() {
        val size = imageBlock()
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 10f, emInPx = 22f)

        assertClose(9.5f, size.heightEm)
    }

    /**
     * 책 스타일시트의 em 너비는 그대로 사용하며 그림의 고유 너비보다 우선한다.
     */
    @Test
    fun emWidthFromTheStylesheetIsUsedVerbatim() {
        val size = imageBlock(aspectRatio = 1f, naturalWidthPx = 800, widthEm = 2.5f)
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertClose(2.5f, size.widthEm)
    }

    /**
     * 가로 구분선은 이미지가 아니다. 열 전체를 차지하고 렌더러가 그리는 고정 높이를 사용한다.
     */
    @Test
    fun aSeparatorIsOneRuleWide() {
        val size = ReaderBlock(kind = ReaderBlockKind.SEPARATOR, range = TextRange(0, 1))
            .readerImageSize(columnWidthEm = 20f, maxHeightEm = 40f, emInPx = 22f)

        assertEquals(20f, size.widthEm)
        assertClose(1.25f, size.heightEm)
    }
}
