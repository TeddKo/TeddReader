package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.standaloneBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 박스로 감싼 플레이트(`<div class="frame"><img/></div>` 형태로 로고나 삽화를 배치하는 방식)가
 * 파서를 통과한 뒤 렌더러가 가운데 정렬 판정을 내리기까지의 동작 형태를 고정한다.
 * 이 테스트가 막는 회귀: 래퍼의 CONTAINER 블록이 이미지를 감싸는 텍스트 블록으로 집계되어
 * 플레이트가 인라인 글리프로 강등되고 왼쪽 정렬로 처리되는 문제.
 */
class EpubPlateAlignmentTest {
    private val css = """
        .br_img{text-align:center;margin-top:7em;}
        .img_britg{text-align:center;text-indent:0em;margin:0 auto;width:6.5em;display:inline-block;}
        .img_britg img{width:100%;}
    """.trimIndent()

    private val xhtml = """
        <html><body>
          <p>prose before</p>
          <div class="br_img">
            <div class="img_britg">
              <a href="https://example.com"><img alt="" src="logo.jpg"/></a>
              <p>caption</p>
            </div>
          </div>
        </body></html>
    """.trimIndent()

    /** 스타일 적용 래퍼 안에 박스로 감싼 플레이트는 여전히 독립(standalone) 상태: 텍스트 블록만이 플레이트를 인라인으로 강등시킨다. */
    @Test
    fun aPlateInsideAStyledWrapperStaysStandalone() {
        val content = parseXhtmlContent(xhtml = xhtml, css = EpubCss.parse(listOf(css)))

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertTrue(content.blocks.any { it.kind == ReaderBlockKind.CONTAINER })
        assertTrue(image in content.blocks.standaloneBlocks())
        assertEquals(ReaderTextAlign.CENTER, image.align)
    }

    /** 문장 안에 삽입된 이미지는 독립(standalone) 상태가 아니다 — 해당 단락이 이미지를 감싸고 있기 때문이다. */
    @Test
    fun aPictureInsideASentenceStaysInline() {
        val content = parseXhtmlContent(
            xhtml = """<p>before <img src="glyph.png"/> after</p>""",
        )

        val image = content.blocks.single { it.kind == ReaderBlockKind.IMAGE }
        assertTrue(image !in content.blocks.standaloneBlocks())
    }

    /** 캐스케이드를 통해 이미지에 전달된 명시적 `text-align: right`가 플레이트를 해당 위치에 배치한다. */
    @Test
    fun anExplicitEndAlignmentIsHonored() {
        val content = parseXhtmlContent(
            xhtml = """<div class="right"><img src="logo.jpg"/></div>""",
            css = EpubCss.parse(listOf(".right{text-align:right;}")),
        )

        assertEquals(ReaderTextAlign.END, content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.align)
    }

    /**
     * `text-align: justify`/`left`는 이미지가 body/p 기본값으로부터 단순히 상속받는 본문 스타일링이며;
     * 리딩 시스템은 그 아래에서도 플레이트를 가운데 정렬하므로 기본값은 그대로 유지된다.
     */
    @Test
    fun inheritedProseAlignmentDoesNotDragThePlateToTheMargin() {
        val content = parseXhtmlContent(
            xhtml = """<div class="prose"><img src="logo.jpg"/></div>""",
            css = EpubCss.parse(listOf(".prose{text-align:justify;}")),
        )

        assertEquals(ReaderTextAlign.CENTER, content.blocks.single { it.kind == ReaderBlockKind.IMAGE }.align)
    }
}
