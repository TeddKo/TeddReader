package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderNavigationItem
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.ViewportSize
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 리더의 페이지 정책 — 예전에는 ReaderViewModel 안에 있어서 가짜 저장소 네 개를 세우고 코루틴 디스패처를
 * 직접 돌려야만 관찰할 수 있었던 결정들 — 을 고정한다.
 *
 * 여기 있는 각 케이스는 독자가 깨졌을 때 바로 알아챌 규칙이다: 재개한 책이 어느 페이지에서 열리는지,
 * 아무것도 측정되기 전에 페이지 카운터가 무엇을 표시하는지, 아웃라인 탭이 어디까지 닿을 수 있는지,
 * pane의 반복된 크기 보고가 책 전체를 다시 측정하게 만드는지.
 */
class ReaderPagePolicyTest {
    private val style = ReaderStyle(fontSizeSp = 18f)
    private val otherStyle = ReaderStyle(fontSizeSp = 24f)
    private val sizePx = ViewportSize(widthPx = 1080, heightPx = 1920)
    private val otherSizePx = ViewportSize(widthPx = 1080, heightPx = 1600)
    private val viewportSp = ViewportSize(widthPx = 360, heightPx = 640)
    private val otherViewportSp = ViewportSize(widthPx = 360, heightPx = 540)

    /** 섹션 두 개를 둔다 — section 상대 EPUB 위치가 0이 아닌 기준점을 두고 해석되도록 하기 위함이다. */
    private fun document(
        format: DocumentFormat = DocumentFormat.EPUB,
        navigation: ReaderNavigation? = null,
        sectionTitles: List<String?> = listOf("Chapter 1", "Chapter 2"),
    ): ReaderDocument = ReaderDocument(
        id = DocumentId("file:///book.epub"),
        format = format,
        title = "Book",
        sections = sectionTitles.mapIndexed { index, title ->
            ReaderSection(
                index = index,
                title = title,
                text = "body",
                range = TextRange(index * 100L, index * 100L + 4L),
            )
        },
        navigation = navigation,
    )

    /** 출판사 스타일 따르기/시스템 따르기는 하나의 적응형 옵션이며, 같은 폴백 정책을 두 행으로 나누지 않는다. */
    @Test
    fun documentDefaultThemeIsOneOptionAdaptedToTheFormat() {
        val publisherOptions = listOf(
            ReaderThemeMode.PUBLISHER,
            ReaderThemeMode.LIGHT,
            ReaderThemeMode.DARK,
            ReaderThemeMode.SEPIA,
        )
        val readerOptions = listOf(
            ReaderThemeMode.SYSTEM,
            ReaderThemeMode.LIGHT,
            ReaderThemeMode.DARK,
            ReaderThemeMode.SEPIA,
        )

        assertEquals(publisherOptions, readerThemeModeOptions(DocumentFormat.EPUB))
        assertEquals(publisherOptions, readerThemeModeOptions(DocumentFormat.PDF))
        listOf(DocumentFormat.TXT, DocumentFormat.CBZ, DocumentFormat.IMAGE, DocumentFormat.UNKNOWN).forEach { format ->
            assertEquals(readerOptions, readerThemeModeOptions(format))
        }
    }

    /** 레거시 값은 양방향으로 정규화되어, 모든 형식에서 이 단일 적응형 행이 선택된다. */
    @Test
    fun documentDefaultThemeModeAdaptsToTheFormat() {
        val publisherStyle = ReaderStyle(themeMode = ReaderThemeMode.PUBLISHER)
        val systemStyle = ReaderStyle(themeMode = ReaderThemeMode.SYSTEM)

        assertEquals(ReaderThemeMode.PUBLISHER, readerStyleForDocumentFormat(publisherStyle, DocumentFormat.EPUB).themeMode)
        assertEquals(ReaderThemeMode.PUBLISHER, readerStyleForDocumentFormat(systemStyle, DocumentFormat.EPUB).themeMode)
        assertEquals(ReaderThemeMode.PUBLISHER, readerStyleForDocumentFormat(systemStyle, DocumentFormat.PDF).themeMode)
        assertEquals(ReaderThemeMode.SYSTEM, readerStyleForDocumentFormat(publisherStyle, DocumentFormat.TXT).themeMode)
        assertEquals(
            ReaderThemeMode.DARK,
            readerStyleForDocumentFormat(ReaderStyle(themeMode = ReaderThemeMode.DARK), DocumentFormat.TXT).themeMode,
        )
    }

    @Test
    fun publisherFontMeasurementWaitsForTheCurrentEmbeddedFontFiles() {
        val embeddedFiles = mapOf("fonts/book.woff2" to "/tmp/book.woff2")
        val publisherStyle = ReaderStyle(fontFamilyName = null)
        val overrideStyle = ReaderStyle(fontFamilyName = "serif")

        assertEquals(
            false,
            readerEmbeddedFontsReadyForMeasurement(
                style = publisherStyle,
                areEmbeddedFontsResolved = true,
                embeddedFontFiles = embeddedFiles,
                loadedEmbeddedFontFiles = null,
            ),
        )
        assertEquals(
            true,
            readerEmbeddedFontsReadyForMeasurement(
                style = publisherStyle,
                areEmbeddedFontsResolved = true,
                embeddedFontFiles = embeddedFiles,
                loadedEmbeddedFontFiles = embeddedFiles,
            ),
        )
        assertEquals(
            true,
            readerEmbeddedFontsReadyForMeasurement(
                style = overrideStyle,
                areEmbeddedFontsResolved = false,
                embeddedFontFiles = embeddedFiles,
                loadedEmbeddedFontFiles = null,
            ),
        )
    }

    /** mount window는 "pager가 미리 준비해 두는 페이지"의 유일한 정의다: 뒤로 둘, 앞으로 셋. */
    @Test
    fun mountWindowSpansTwoPagesBackAndThreeForward() {
        assertEquals(3..8, pagerMountWindow(5))
    }

    /** 여기서는 clamp하지 않는다 — 0페이지 근처의 호출자는 음수 절반을 그대로 받아 스스로 걸러낸다. */
    @Test
    fun mountWindowIsNotClampedAtTheStartOfTheBook() {
        assertEquals(-2..3, pagerMountWindow(0))
    }

    /**
     * visual 문서의 아웃라인은 페이지당 항목 하나이며, 각 항목은 [ReaderLocation.PdfPage]다.
     *
     * 이 형태는 구조적으로 중요하다: PDF나 만화에서 아웃라인 탭이 만들어내는 유일한 위치 종류이며,
     * text-offset 조회로는 이를 해석할 수 없다 — 그래서 `moveToLocation`은 이를 위한 전용 분기를 둔다.
     */
    @Test
    fun visualOutlineIsOnePdfPageEntryPerPage() {
        val items = readerOutlineItems(DocumentFormat.CBZ, document(format = DocumentFormat.CBZ), totalPages = 3)

        assertEquals(3, items.size)
        assertEquals(listOf("Page 1", "Page 2", "Page 3"), items.map { it.title })
        assertEquals(
            listOf(ReaderLocation.PdfPage(0), ReaderLocation.PdfPage(1), ReaderLocation.PdfPage(2)),
            items.map { it.location },
        )
    }

    /** 자체 목차를 가진 책은 그것을, 선언된 레벨과 함께 그대로 보여준다. */
    @Test
    fun epubOutlineUsesTheBooksOwnNavigationWhenItHasSome() {
        val navigation = ReaderNavigation(
            items = listOf(
                ReaderNavigationItem(title = "Preface", level = 1, spineIndex = 0, offset = 0),
                ReaderNavigationItem(title = "Part I", level = 2, spineIndex = 1, offset = 12),
            ),
        )
        val items = readerOutlineItems(DocumentFormat.EPUB, document(navigation = navigation), totalPages = 9)

        assertEquals(listOf("Preface", "Part I"), items.map { it.title })
        assertEquals(listOf(1, 2), items.map { it.level })
        assertEquals(ReaderLocation.EpubOffset(1, 12), items[1].location)
    }

    /** navigation이 없으면 섹션 자체가 아웃라인이 되므로, 책은 항상 아웃라인을 하나 갖는다. */
    @Test
    fun epubOutlineFallsBackToSections() {
        val items = readerOutlineItems(DocumentFormat.EPUB, document(), totalPages = 9)

        assertEquals(listOf("Chapter 1", "Chapter 2"), items.map { it.title })
        assertEquals(ReaderLocation.EpubOffset(0, 0), items.first().location)
    }

    /** 제목 없는 섹션도 빈 행이 아니라 사용 가능한 레이블을 받는다. */
    @Test
    fun sectionOutlineNamesAnUntitledSectionByItsNumber() {
        val items = readerOutlineItems(
            DocumentFormat.EPUB,
            document(sectionTitles = listOf(null, "Chapter 2")),
            totalPages = 9,
        )

        assertEquals("Section 1", items.first().title)
    }

    /** 순수 텍스트 문서의 아웃라인은 spine 위치가 아니라 절대 text offset을 가리킨다. */
    @Test
    fun textOutlinePointsAtAbsoluteOffsets() {
        val items = readerOutlineItems(DocumentFormat.TXT, document(format = DocumentFormat.TXT), totalPages = 4)

        assertEquals(ReaderLocation.TextOffset(0), items.first().location)
        assertEquals(ReaderLocation.TextOffset(100), items[1].location)
    }

    /**
     * 리더가 이미 갖고 있는 측정값을 그대로 설명하는 보고는 무시된다.
     *
     * 보고하는 pane은 페이지를 넘길 때마다 새 breaker 인스턴스를 구성하며, 페이지 효과가 애니메이션되는
     * 동안 페이지를 두 번 구성할 수도 있다. 이를 새로운 측정값으로 취급하면 넘길 때마다 문서 전체를 다시
     * 레이아웃하게 되므로, 비교는 인스턴스가 아니라 측정값이 무엇을 설명하는지를 기준으로 한다.
     */
    @Test
    fun paneReportIsIgnoredWhenItDescribesTheMeasurementAlreadyHeld() {
        assertEquals(
            PaneReportOutcome.Ignore,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = sizePx,
                hasBreaker = true,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /**
     * 저장된 레이아웃이 채택된 뒤 처음 도착하는 실제 보고는 다시 로드하지 않고 기록된다.
     *
     * 문서를 열면 어떤 pane이 보고하기도 전에, breaker 자체는 아직 없는 상태로, 저장된 레이아웃의
     * viewport와 그것이 측정될 때 쓰인 스타일을 채택한다. 이 보고는 그 채택을 확인하는 것일 뿐이다 —
     * 같은 물리 화면이므로 페이지들이 이미 캐시된 것과 같은 sp 크기이며, 다시 로드하는 것은 같은 답을
     * 한 번 더 묻는 셈이다.
     */
    @Test
    fun paneReportIsRecordedWithoutReloadWhenItConfirmsAnAdoptedViewport() {
        assertEquals(
            PaneReportOutcome.RecordOnly,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = null,
                hasBreaker = false,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** 다른 type에 대한 보고는 실제 측정값 변화다: 책을 다시 레이아웃해야 한다. */
    @Test
    fun paneReportTriggersReloadForADifferentStyle() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = otherStyle,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = sizePx,
                hasBreaker = true,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** 같은 type에서 다른 pane 크기에 대한 보고도 마찬가지다. */
    @Test
    fun paneReportTriggersReloadForADifferentMeasuredSize() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = otherSizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = sizePx,
                hasBreaker = true,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /**
     * 채택된 것과 sp viewport가 다른 첫 보고는 확인이 아니다 — 채택된 크기로 캐시된 페이지들은 다른 박스를
     * 설명하므로, 이번에는 측정해야 한다.
     */
    @Test
    fun paneReportTriggersReloadWhenTheAdoptedViewportDoesNotMatch() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = otherViewportSp,
                currentBreakerStyle = style,
                currentBreakerSizePx = null,
                hasBreaker = false,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** 아무것도 채택되지 않고 아무것도 측정되지 않은 상태에서, 맨 처음 보고는 실제 측정값이다. */
    @Test
    fun theFirstReportOfAFreshDocumentTriggersReload() {
        assertEquals(
            PaneReportOutcome.RecordAndReload,
            paneReportOutcome(
                reportedStyle = style,
                reportedSizePx = sizePx,
                reportedViewportSp = viewportSp,
                currentBreakerStyle = null,
                currentBreakerSizePx = null,
                hasBreaker = false,
                currentViewportSp = viewportSp,
            ),
        )
    }

    /** 문서 전체 측정과 viewport 보고는 primary pane만이 담당한다. */
    @Test
    fun secondaryPaneDoesNotCreateAPageBreaker() {
        assertEquals(true, readerPagePaneShouldMeasure(reportViewportSize = true))
        assertEquals(false, readerPagePaneShouldMeasure(reportViewportSize = false))
    }
}
