package com.tedd.teddreader.core.common.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 리더 자체 상태의 기반인 계산과 테마 규칙을 고정한다. 페이지 인덱스에서 진행률을 파생하는 방식, 텍스트 범위를 뒤집을 수 없다는 조건, 문서 개수가 섹션에서 나온다는 사실, 내장 테마 적용 시 독자가 선택한 활자는 유지하면서 색상을 교체하는 동작을 다룬다.
 *
 * 자동 스크롤 경우는 열거형 값이 아니라 순서를 보호한다. 설정 화면이 가장 세밀한 것부터 가장 거친 것까지 모드를 표시하므로 `LINE`은 `PIXEL`과 `PAGE` 사이에 있다.
 */
class ReaderModelsTest {
    @Test
    fun pageProgressUsesCanonicalPageIndex() {
        assertEquals(0.5f, PageIndex(current = 5, total = 10).progress)
    }

    @Test
    fun textRangeRejectsInvalidOrder() {
        assertFailsWith<IllegalArgumentException> {
            TextRange(start = 10, end = 1)
        }
    }

    @Test
    fun readerDocumentCalculatesCharacterAndWordCount() {
        val document = ReaderDocument(
            id = DocumentId("doc-1"),
            format = DocumentFormat.TXT,
            title = "Sample",
            sections = listOf(
                ReaderSection(
                    index = 0,
                    text = "hello reader",
                    range = TextRange(0L, 12L),
                ),
            ),
        )

        assertEquals(12L, document.characterCount)
        assertEquals(2L, document.wordCount)
    }

    @Test
    fun autoScrollModeEntriesIncludeLineBetweenPixelAndPage() {
        assertContentEquals(
            listOf(AutoScrollMode.PIXEL, AutoScrollMode.LINE, AutoScrollMode.PAGE),
            AutoScrollMode.entries,
        )
    }

    @Test
    fun withThemeModePreservesTypographyAndUpdatesBuiltInThemeColors() {
        val style = ReaderStyle(
            fontSizeSp = 24f,
            fontFamilyName = "serif",
            lineHeightMultiplier = 1.8f,
            textColor = ReaderColor(0xFF010203),
            backgroundColor = ReaderColor(0xFF040506),
            backgroundImage = BackgroundImage(uri = "file:///bg.png", opacity = 0.5f),
            themeMode = ReaderThemeMode.CUSTOM,
        )

        val publisher = style.withThemeMode(ReaderThemeMode.PUBLISHER)
        val dark = style.withThemeMode(ReaderThemeMode.DARK)
        val system = style.withThemeMode(ReaderThemeMode.SYSTEM)
        val custom = style.withThemeMode(ReaderThemeMode.CUSTOM)

        assertEquals(24f, publisher.fontSizeSp)
        assertEquals("serif", publisher.fontFamilyName)
        assertEquals(1.8f, publisher.lineHeightMultiplier)
        assertEquals(ReaderColor(ReaderLightTextArgb), publisher.textColor)
        assertEquals(ReaderColor(ReaderLightBackgroundArgb), publisher.backgroundColor)
        assertEquals(null, publisher.backgroundImage)
        assertEquals(ReaderThemeMode.PUBLISHER, publisher.themeMode)

        assertEquals(24f, dark.fontSizeSp)
        assertEquals("serif", dark.fontFamilyName)
        assertEquals(1.8f, dark.lineHeightMultiplier)
        assertEquals(ReaderColor(ReaderDarkTextArgb), dark.textColor)
        assertEquals(ReaderColor(ReaderDarkBackgroundArgb), dark.backgroundColor)
        assertEquals(null, dark.backgroundImage)
        assertEquals(ReaderThemeMode.DARK, dark.themeMode)

        assertEquals(ReaderColor(ReaderLightTextArgb), system.textColor)
        assertEquals(ReaderColor(ReaderLightBackgroundArgb), system.backgroundColor)
        assertEquals(ReaderThemeMode.SYSTEM, system.themeMode)
        assertEquals(null, system.backgroundImage)

        assertEquals(ReaderColor(0xFF010203), custom.textColor)
        assertEquals(ReaderColor(0xFF040506), custom.backgroundColor)
        assertEquals(ReaderThemeMode.CUSTOM, custom.themeMode)
        assertEquals(BackgroundImage(uri = "file:///bg.png", opacity = 0.5f), custom.backgroundImage)
    }

    @Test
    fun layoutKeyFallsBackToPublisherFontKeyWhenNoUserFontIsChosen() {
        assertEquals(
            "loaded-fonts#layout8",
            ReaderStyle(publisherFontKey = "loaded-fonts").layoutKey().fontFamilyName,
        )
        assertEquals(
            "serif#layout8",
            ReaderStyle(fontFamilyName = "serif", publisherFontKey = "loaded-fonts").layoutKey().fontFamilyName,
        )
        assertEquals(
            "same-href=loaded#layout8",
            ReaderStyle(publisherFontKey = "same-href=loaded").layoutKey().fontFamilyName,
        )
    }

    /**
     * 레이아웃 알고리즘 표식은 이전 알고리즘이 저장한 모든 페이지 레이아웃을 명확한 캐시 미스로 만드는 값이다. 저장 키에는 이 값이 없거나 이전 값이 들어 있으므로 알고리즘 변경 뒤의 조회가 동일한 텍스트에 오래된 페이지 경계를 제공할 수 없다.
     */
    @Test
    fun layoutKeyCarriesTheLayoutAlgorithmVersion() {
        assertEquals("#layout8", ReaderStyle().layoutKey().fontFamilyName)
    }

    /**
     * 시스템을 따르는 스타일은 다크 모드 기기에서 어두운 페이지 색상으로 그린다.
     *
     * 이 회귀는 앱 절반만 다크 모드인 상태로 배포됐다. UI 외곽은 시스템 플래그를 해석해 어두워졌지만 페이지는 `SYSTEM` 선택 시 영속화한 밝은 색상을 유지하여, 리더가 어두운 프레임 안에 밝은 종이를 표시했다.
     */
    @Test
    fun systemThemeTakesDarkPageColoursOnADarkDevice() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM).resolveSystemTheme(true)

        assertEquals(ReaderColor(ReaderDarkBackgroundArgb), resolved.backgroundColor)
        assertEquals(ReaderColor(ReaderDarkTextArgb), resolved.textColor)
    }

    /**
     * 책이 자체 색상을 제공하지 않을 때 출판사/문서 스타일도 앱 UI 외곽과 같은 실시간 시스템 대체값을 따른다. 명시적 EPUB 색상은 계속 PUBLISHER 모드에서만 적용된다.
     */
    @Test
    fun publisherThemeTakesDarkFallbackColoursOnADarkDevice() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.PUBLISHER).resolveSystemTheme(true)

        assertEquals(ReaderColor(ReaderDarkBackgroundArgb), resolved.backgroundColor)
        assertEquals(ReaderColor(ReaderDarkTextArgb), resolved.textColor)
        assertEquals(ReaderThemeMode.PUBLISHER, resolved.themeMode)
    }

    /**
     * 시스템에 맞게 색상을 해석해도 모드는 `SYSTEM`으로 유지되므로, 설정을 다시 읽을 때 명시적 다크 모드 선택으로 자체 변경된 것처럼 보이지 않고 계속 "시스템 설정 따르기"로 나타난다.
     */
    @Test
    fun resolvingForTheSystemDoesNotRewriteTheChosenMode() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM).resolveSystemTheme(true)

        assertEquals(ReaderThemeMode.SYSTEM, resolved.themeMode)
    }

    /**
     * 라이트 모드 기기에서는 같은 스타일이 라이트 모드로 유지되며, 영속화된 값도 이미 이 상태다.
     */
    @Test
    fun systemThemeStaysLightOnALightDevice() {
        val resolved = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM).resolveSystemTheme(false)

        assertEquals(ReaderColor(ReaderLightBackgroundArgb), resolved.backgroundColor)
        assertEquals(ReaderColor(ReaderLightTextArgb), resolved.textColor)
    }

    /**
     * 명시적 테마는 시스템 플래그를 무시한다. 라이트 모드 선택 자체가 시스템을 따르지 않겠다는 결정이다.
     *
     * 다크 모드 기기에서 구체적으로 확인한다. 잘못된 해석이 끼어들면 사용자의 명시적 선택이 재정의되는 방향이기 때문이다.
     */
    @Test
    fun anExplicitThemeIgnoresTheSystemSetting() {
        val light = ReaderStyle().withThemeMode(ReaderThemeMode.LIGHT).resolveSystemTheme(true)
        val sepia = ReaderStyle().withThemeMode(ReaderThemeMode.SEPIA).resolveSystemTheme(true)

        assertEquals(ReaderColor(ReaderLightBackgroundArgb), light.backgroundColor)
        assertEquals(ReaderColor(ReaderSepiaBackgroundArgb), sepia.backgroundColor)
    }

    /**
     * 색상 해석은 저장 페이지 나누기를 절대 무효화하지 않는다.
     *
     * 페이지 경계는 활자 크기, 줄 높이, 패밀리, 굵기를 키로 사용한다. 색상이 그 키에 들어가면 시스템 테마가 바뀔 때마다 열린 문서가 조용히 다시 페이지로 나뉜다.
     */
    @Test
    fun resolvingForTheSystemLeavesThePaginationKeyAlone() {
        val stored = ReaderStyle().withThemeMode(ReaderThemeMode.SYSTEM)

        assertEquals(stored.layoutKey(), stored.resolveSystemTheme(true).layoutKey())
    }

    /**
     * [ReaderStyle.withLayoutFieldsOf]는 [layoutKey]가 스타일에서 추리는 필드만 정확히 복사하고 다른 것은 복사하지 않아야 한다. 오래된 페이지 조각 수정 전체가 의존하는 "활자 고정, 색상 즉시 반영" 계약을 한 곳에서 고정한다. [live]와 [measured]는 레이아웃 및 비레이아웃의 *모든* 필드가 다르도록 만든다. 누군가 [layoutKey]에 다섯 번째 필드를 추가하면서 [ReaderStyle.withLayoutFieldsOf] 복사기에는 추가하지 않으면 이 테스트가 실패한다. 지금 `fontFamilyName`을 복사기에서 제거해도 정확히 같은 방식으로 실패한다.
     */
    @Test
    fun withLayoutFieldsOfCopiesOnlyTheLayoutKeyFieldsAndKeepsEveryColourFieldLive() {
        val live = ReaderStyle(
            fontSizeSp = 18f,
            fontFamilyName = "serif",
            publisherFontKey = "live-publisher-key",
            lineHeightMultiplier = 1.4f,
            fontWeight = 300,
            textColor = ReaderColor(0xFF010203),
            backgroundColor = ReaderColor(0xFF040506),
            backgroundImage = BackgroundImage(uri = "file:///live.png", opacity = 0.3f),
            themeMode = ReaderThemeMode.CUSTOM,
        )
        val measured = ReaderStyle(
            fontSizeSp = 24f,
            fontFamilyName = "sans",
            publisherFontKey = "measured-publisher-key",
            lineHeightMultiplier = 1.8f,
            fontWeight = 600,
            textColor = ReaderColor(0xFF0A0B0C),
            backgroundColor = ReaderColor(0xFF0D0E0F),
            backgroundImage = BackgroundImage(uri = "file:///measured.png", opacity = 0.6f),
            themeMode = ReaderThemeMode.DARK,
        )
        val drawn = live.withLayoutFieldsOf(measured)

        assertEquals(measured.layoutKey(), drawn.layoutKey())
        assertEquals(measured.fontWeight, drawn.fontWeight)
        assertEquals(live.textColor, drawn.textColor)
        assertEquals(live.backgroundColor, drawn.backgroundColor)
        assertEquals(live.themeMode, drawn.themeMode)
        assertEquals(live.backgroundImage, drawn.backgroundImage)
    }

    /**
     * [ReaderStyle.fontWeight]가 [ReaderDefaultFontWeight]와 다르면 더 무겁거나 가벼운 굵기가 글리프 진행 폭과 줄바꿈 위치를 바꾸므로 [layoutKey]도 달라져야 한다. 반면 기본 굵기를 계속 사용하는 독자에게는 키가 현재와 정확히 같아야 한다. 그래야 이 설정을 출시한 날 기존 독자의 저장 페이지 레이아웃을 강제로 다시 측정하지 않고 계속 사용할 수 있다.
     *
     * 반증(AGENTS.md 반증 절차): 예를 들어 `fontWeightToken()` 본문이 항상 `""`을 반환하도록 바꿔 [layoutKey]의 계산된 패밀리 문자열에서 `fontWeight`를 다시 제외하고 이 테스트 모음을 실행한다. 실제 결과는 이 테스트의 두 번째 단언 실패이다. `expected:<|w600#layout8> but was:<#layout8>`이며, 굵기 600 스타일의 키가 기본 굵기 스타일과 달라지지 않고 같아지기 때문이다. 첫 단언은 기본 굵기만 검사하므로 계속 통과한다. 토큰을 복원하면 두 단언 모두 다시 통과한다.
     */
    @Test
    fun nonDefaultFontWeightChangesLayoutKeyButDefaultWeightDoesNot() {
        assertEquals("#layout8", ReaderStyle(fontWeight = ReaderDefaultFontWeight).layoutKey().fontFamilyName)
        assertEquals("|w600#layout8", ReaderStyle(fontWeight = 600).layoutKey().fontFamilyName)
    }

    /**
     * [ReaderStyle]의 `init`은 네 가지 타이포그래피 설정이 제공하는 300..600 범위 밖의 글꼴 굵기를 거부한다. [ReaderStyle.fontSizeSp]와 [ReaderStyle.lineHeightMultiplier]가 각자 범위에 이미 적용하는 것과 같은 방어적 경계이다.
     */
    @Test
    fun readerStyleRejectsFontWeightOutsideSupportedRange() {
        assertFailsWith<IllegalArgumentException> {
            ReaderStyle(fontWeight = 299)
        }
        assertFailsWith<IllegalArgumentException> {
            ReaderStyle(fontWeight = 601)
        }
    }
}
