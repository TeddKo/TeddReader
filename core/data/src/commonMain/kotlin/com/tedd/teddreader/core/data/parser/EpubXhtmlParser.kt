package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBorder
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderFontFamily
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderInlineStyle
import com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar
import com.tedd.teddreader.core.common.model.ReaderSpan
import com.tedd.teddreader.core.common.model.ReaderSpanStyle
import com.tedd.teddreader.core.common.model.ReaderTextAlign
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isBlankIgnoringObjects
import kotlin.math.abs

/**
 * 한 챕터의 평탄화된 텍스트와 해당 텍스트가 담고 있는 구조.
 *
 * 텍스트는 리더가 페이지를 나누고, 검색하고, 저장하는 대상이며, 블록과 스팬은 그 내부의 범위를 가리킨다.
 * 태그를 모두 공백으로 대체하면 이 정보가 사라지고 줄바꿈까지 뭉개지는데, 바로 그 때문에
 * EPUB이 줄바꿈 없는 한 덩어리로 읽히던 문제가 생겼다.
 */
internal data class XhtmlContent(
    /** 챕터의 가독 텍스트. 모든 태그가 제거되되 구조는 줄바꿈으로 보존된다. */
    val text: String,
    /**
     * [text] 위의 구조적 스팬 — 제목, 단락, 이미지, 표 셀 등 — 읽기 순서 기준.
     */
    val blocks: List<ReaderBlock>,
    /**
     * 이 챕터에서 선언된 모든 `id`/`name`/`xml:id`를 [text] 내의 절대 오프셋에 매핑한 것.
     * 내부 링크의 프래그먼트를 위치로 해석하는 데 사용된다.
     */
    val anchors: Map<String, Long> = emptyMap(),
    /**
     * 첫 번째 제목의 `title` 속성에서 가져온 챕터 이름. 이 책들은 파트와 챕터 제목을 이미지로
     * 표시하고 가독 이름은 해당 속성에만 넣기 때문에, 이것이 없으면 챕터에 제목 텍스트가 전혀 없다.
     */
    val headingTitle: String? = null,
)

/**
 * 챕터 XHTML을 텍스트와 블록으로 변환한다. 모든 오프셋은 [baseOffset]을 기준으로 표현되므로,
 * 챕터 내용을 하나의 문서로 연결할 때 아무것도 재계산할 필요가 없다.
 *
 * 파서는 단일 순방향 스캔으로 동작한다: 완전한 XML/HTML 파서가 아니며 DOM을 만들지 않고,
 * 태그나 텍스트 런을 하나씩 처리하는 블록/스팬 빌더([XhtmlContentBuilder])만 운용한다.
 * `<script>`, `<style>`, `<head>`, `<title>` 바디는 [SkippedBodyElements] 참조로 즉시 건너뛴다 —
 * 이것들은 가독 텍스트를 담지 않으며, 건너뜀으로써 CSS 소스와 스크립트 코드가 페이지에 들어오지 않는다.
 * 단, `<svg>`는 인식된 블록이나 인라인 태그가 아님에도 불구하고 의도적으로 이와 같이 처리하지 않는다:
 * EPUB은 전체 페이지 일러스트를 `<svg><image xlink:href="..."/></svg>` 형식으로 감싸는 경우가
 * 매우 많으며(Sigil/Calibre의 표준 표지/일러스트 패턴), 이는 뷰포트에 맞게 크기가 조정된다.
 * 그런데 script/style처럼 전체 서브트리를 건너뛰면 그 그림들이 모두 조용히 사라진다.
 * `svg` 안으로 내려가는 것은 무해하다: 인식된 블록이나 인라인 태그가 없으므로 그 외에는 무시되고,
 * 내부 `image` 요소도 다른 것과 동일하게 처리된다. 잘못된 마크업은 예외를 던지지 않고 소프트하게 실패한다:
 * 닫히지 않은 주석, CDATA 섹션, 태그는 나머지 입력을 자신의 본문이나 텍스트로 소비하고,
 * 끝에서 닫히지 않은 인라인 또는 블록 요소는 [XhtmlContentBuilder.build]에서 묵시적으로 닫힌다.
 *
 * @param xhtml 챕터의 원시 마크업.
 * @param baseOffset [XhtmlContent.text] 시작 위치가 나타내는 절대 오프셋. 여러 챕터를 연결하는 호출자가
 *   이전 챕터들의 합산 길이를 전달하면, 이 파일 하나가 아닌 책 전체에 이미 맞춰진 범위를 얻을 수 있다.
 * @param resolveImageHref 원시 `src`/`xlink:href`/`href`를 컨테이너 내 경로로 매핑하거나, 이미지를
 *   버려야 하면 null을 반환한다 — 예: 이 리더가 가져올 수 없는 원격 `http(s)://` URL.
 * @param css 이 챕터 요소 계층에 적용되는 전체 CSS 캐스케이드 — 정렬, 굵기, 스타일, 글꼴 패밀리,
 *   줄 높이, 들여쓰기, 단락 간격, 이미지 너비 모두 포함. 기본값은 [EpubCss.Empty].
 * @return 이 챕터에 대한 평탄화된 [XhtmlContent].
 */
internal fun parseXhtmlContent(
    xhtml: String,
    baseOffset: Long = 0L,
    resolveImageHref: (String) -> String? = { it },
    css: EpubCss = EpubCss.Empty,
): XhtmlContent {
    val builder = XhtmlContentBuilder(
        baseOffset = baseOffset,
        resolveImageHref = resolveImageHref,
        css = css,
    )
    var index = 0
    while (index < xhtml.length) {
        val tagStart = xhtml.indexOf('<', index)
        if (tagStart < 0) {
            builder.appendText(xhtml.substring(index))
            break
        }
        if (tagStart > index) builder.appendText(xhtml.substring(index, tagStart))

        if (xhtml.startsWith("<!--", tagStart)) {
            index = xhtml.skipPast(tagStart, "-->")
            continue
        }
        if (xhtml.startsWith("<![CDATA[", tagStart)) {
            val end = xhtml.indexOf("]]>", tagStart)
            if (end < 0) {
                builder.appendText(xhtml.substring(tagStart + 9))
                break
            }
            builder.appendText(xhtml.substring(tagStart + 9, end))
            index = end + 3
            continue
        }

        val tagEnd = xhtml.indexOf('>', tagStart)
        if (tagEnd < 0) break
        val raw = xhtml.substring(tagStart + 1, tagEnd)
        index = tagEnd + 1

        if (raw.startsWith("!") || raw.startsWith("?")) continue
        val tag = parseTag(raw)

        if (!tag.isClosing && tag.name in SkippedBodyElements) {
            if (tag.isSelfClosing) continue
            index = xhtml.skipPast(index, "</${tag.name}")
            index = xhtml.indexOf('>', index).let { if (it < 0) xhtml.length else it + 1 }
            continue
        }

        if (tag.isClosing) builder.closeElement(tag.name) else builder.openElement(tag)
    }
    return builder.build()
}

/**
 * [from] 이후에서 [marker]가 처음 나타나는 위치의 바로 다음 인덱스를 반환하고,
 * [marker]가 없으면 [String.length]를 반환한다. 잘못된 마크업에서 닫는 마커가 빠진
 * 주석, CDATA 섹션, 건너뛸 요소 본문을 영원히 찾아다니지 않고 단번에 넘어가기 위해 사용된다.
 *
 * @receiver 스캔 중인 마크업.
 * @param from 검색을 시작할 인덱스.
 * @param marker 찾고 있는 닫는 텍스트, 예: `"-->"` 또는 `"</script"`.
 */
private fun String.skipPast(from: Int, marker: String): Int {
    val found = indexOf(marker, from)
    return if (found < 0) length else found + marker.length
}

/**
 * [parseTag]가 읽어낸 시작 또는 종료 태그 하나. 블록, 인라인 스타일, 또는 완전히 무시할 대상으로
 * 해석되기 전의 상태.
 */
private class XhtmlTag(
    /** 소문자로 변환된 태그 이름, 예: `"p"` 또는 `"img"`. */
    val name: String,
    /** 속성 이름을 값에 매핑한 것, 이름은 소문자; 닫는 태그는 빈 맵. */
    val attributes: Map<String, String>,
    /** `</name>` 형식이면 true. */
    val isClosing: Boolean,
    /** `<name .../>` 형식이면 true. */
    val isSelfClosing: Boolean,
)

/** 태그의 원시 내부(`<`/`</`와 닫는 `>` 사이)를 [XhtmlTag]로 파싱한다. */
private fun parseTag(raw: String): XhtmlTag {
    val isClosing = raw.startsWith("/")
    val body = raw.removePrefix("/").removeSuffix("/")
    val name = body.takeWhile { !it.isWhitespace() }.lowercase()
    return XhtmlTag(
        name = name,
        attributes = if (isClosing) emptyMap() else parseTagAttributes(body),
        isClosing = isClosing,
        isSelfClosing = raw.endsWith("/"),
    )
}

/** 태그 본문의 `name="value"`/`name='value'` 쌍을 소문자 키 맵으로 파싱한다. */
private fun parseTagAttributes(body: String): Map<String, String> =
    TagAttributeRegex.findAll(body).associate { match ->
        match.groupValues[1].lowercase() to (match.groupValues[2].ifEmpty { match.groupValues[3] })
    }

/**
 * [XhtmlContentBuilder]의 스택에 현재 열려 있는 요소. CSS 계층 매칭과 이름 기반 닫기를 위해 보관한다.
 */
private class OpenElement(
    /** 소문자로 변환된 태그 이름. */
    val name: String,
    /** 마크업 순서 기준 이 요소 자신의 클래스 목록. */
    val classNames: List<String>,
    /** 요소의 `id` 속성, 없으면 null. */
    val id: String?,
    /** 이 요소의 인라인 `style` 선언. 같은 요소에 적용된 링크된 CSS보다 우선한다. */
    val inlineDeclarations: CssDeclarations = CssDeclarations.Empty,
    /** 이 요소의 완전히 해석된 스타일; [ComputedStyle] 참조. */
    val computed: ComputedStyle = ComputedStyle.Root,
) {
    /**
     * [EpubCss.declarationsFor]가 필요로 하는 형태로 이 요소를 표현한 것. 한 번 빌드되어
     * 모든 자식의 캐스케이드 조회에 재사용된다.
     */
    val cssElement: CssElement = CssElement(tag = name, classes = classNames.toSet(), id = id)
}

/**
 * 해석기가 전달하는 `line-height`: 적용될 글꼴의 배율 인수로 남아 있거나,
 * 리더의 기준 em 단위로 이미 고정된 크기.
 */
private sealed interface ResolvedLineHeight {
    /** 단위 없는 배율. 소비 시 각 요소 자신의 [ComputedStyle.fontScale]과 곱해진다. */
    data class Factor(val value: Float) : ResolvedLineHeight
    /** 선언된 요소에서 이미 계산된 길이, 기준 em 단위. */
    data class BaseEm(val value: Float) : ResolvedLineHeight
}

/**
 * 전체 캐스케이드가 이미 해석된 한 요소의 스타일 — CSS가 원시 선언에서 벗어나
 * 렌더러가 어떤 재해석 없이 소비할 수 있는 수치로 바뀌는 단 한 곳.
 *
 * 여기의 모든 길이는 하나의 좌표계, 즉 **리더의 기준 em 단위**로 표현된다. 이것이
 * 네 소비자(블록 스타일링, 스팬 스타일링, 간격 크기 조정, 박스 페인팅)가 서로 일치하게 만드는
 * 계약이다 — 책 안의 `em`은 각 소비자가 상대값을 추측하는 대신 *여기서* 한 번만 조상을 통해 복합된다.
 */
private class ComputedStyle(
    /**
     * 이 요소의 유효 선언: 상속된 텍스트 속성(굵기, 스타일, 패밀리, 색상, 정렬)에
     * 요소 자신의 선언이 위에 덧씌워진 것. 비상속 속성(마진, 패딩, 테두리, 디스플레이, 너비, 플로트)은
     * 요소 자신의 것만.
     */
    val declarations: CssDeclarations,
    /** 리더 기준값의 배수로 표현된 글꼴 크기. 조상 체인을 통해 복합된다. */
    val fontScale: Float,
    /** 선언 또는 상속된 줄 높이. 체인 어디에도 명시되지 않으면 null. */
    val lineHeight: ResolvedLineHeight?,
    /** 기준 em 단위의 `text-indent`. 계산된 값으로 상속된다; 명시되지 않으면 null. */
    val textIndentEm: Float?,
    /** 이 요소(자신 또는 조상)에 밑줄이 그려지는지 여부; null = 아무것도 명시되지 않음. */
    val underline: Boolean?,
    /** 같은 조건으로 이 요소에 취소선이 그려지는지 여부. */
    val lineThrough: Boolean?,
    /**
     * 모든 블록 레벨 조상의 margin+padding과 이 요소 자신의 것을 합산한 인라인-스타트 공간,
     * 기준 em 단위. 들여쓰기된 래퍼 안에 중첩된 단락을 들여쓰는 값이다.
     * 페이지 컨테이너(`html`/`body`)는 제외 — 그것들의 간격은 대신 페이지 마진이 된다.
     */
    val insetStartEm: Float,
    /** [insetStartEm]의 인라인-엔드 대응값. */
    val insetEndEm: Float,
) {
    /** 이 요소 자신의 텍스트가 설정된 줄 높이, 기준 em 단위. 명시되지 않으면 null. */
    fun lineHeightBaseEm(): Float? = when (val height = lineHeight) {
        is ResolvedLineHeight.Factor -> height.value * fontScale
        is ResolvedLineHeight.BaseEm -> height.value
        null -> null
    }

    /**
     * 이 스타일의 블록 뒤에 빈 줄이 들어가야 하는지 여부. `margin-bottom: 0`은
     * 단락이 간격 없이 이어지는 고전적인 들여쓰기 산문 설정이며, 그 외에는 간격을 유지한다.
     */
    fun separatesParagraphs(): Boolean {
        val bottom = declarations.marginBottom?.toResolvedMarginEm(fontScale) ?: return true
        return bottom > 0f
    }

    companion object {
        /** 문서 루트가 상속받는 것: 아무것도 명시되지 않음, 기본 타입, 인셋 없음. */
        val Root = ComputedStyle(
            declarations = CssDeclarations.Empty,
            fontScale = 1f,
            lineHeight = null,
            textIndentEm = null,
            underline = null,
            lineThrough = null,
            insetStartEm = 0f,
            insetEndEm = 0f,
        )
    }
}

/**
 * 한 요소의 [ComputedStyle]을 부모의 것에서 점진적으로 해석한다 — 전체 조상 체인을 매번 다시 접는 대신
 * 한 단계씩 증가하므로, 캐스케이드가 요소당 O(depth²)가 아닌 O(depth)가 된다.
 *
 * @param parent 감싸는 요소의 계산된 스타일, 최상위에서는 [ComputedStyle.Root].
 * @param css 챕터의 스타일시트.
 * @param ancestry 요소 자신 포함 CSS 계층, 가장 바깥쪽부터.
 * @param inline 요소 자신의 `style=""` 선언. 스타일시트보다 우선한다.
 * @param accumulatesInset 이 요소 자신의 start/end margin+padding이 자식들의 레이아웃 인셋에 합산되는지 여부.
 * 블록 레벨 래퍼는 true, 인라인 요소와 간격이 페이지 마진이 되는 페이지 컨테이너는 false.
 */
private fun resolveComputedStyle(
    parent: ComputedStyle,
    css: EpubCss,
    ancestry: List<CssElement>,
    inline: CssDeclarations,
    accumulatesInset: Boolean,
): ComputedStyle {
    val own = css.declarationsFor(ancestry).mergedWith(inline)
    val inheritedBase = parent.declarations.inheritable()
    val effective = inheritedBase.mergedWith(own).resolvedInheritedKeywords(inheritedBase)
    val fontScale = own.fontSize.resolveFontScale(parent.fontScale)
    val lineHeight = when (val declared = own.lineHeight) {
        is CssLineHeight.Factor -> declared.value.takeIf { it > 0f }?.let(ResolvedLineHeight::Factor) ?: parent.lineHeight
        is CssLineHeight.Length -> declared.length.toResolvedLineHeightEm(fontScale)?.let(ResolvedLineHeight::BaseEm) ?: parent.lineHeight
        null -> parent.lineHeight
    }
    val textIndentEm = own.textIndent?.toResolvedIndentEm(fontScale) ?: parent.textIndentEm
    val underline = own.textDecoration?.toDecorationFlag("underline") ?: parent.underline
    val lineThrough = own.textDecoration?.toDecorationFlag("line-through") ?: parent.lineThrough
    val insetStart = if (accumulatesInset) {
        (own.marginLeft?.toResolvedMarginEm(fontScale) ?: 0f) + (own.paddingLeft?.toResolvedMarginEm(fontScale) ?: 0f)
    } else {
        0f
    }
    val insetEnd = if (accumulatesInset) {
        (own.marginRight?.toResolvedMarginEm(fontScale) ?: 0f) + (own.paddingRight?.toResolvedMarginEm(fontScale) ?: 0f)
    } else {
        0f
    }
    return ComputedStyle(
        declarations = effective,
        fontScale = fontScale,
        lineHeight = lineHeight,
        textIndentEm = textIndentEm,
        underline = underline,
        lineThrough = lineThrough,
        insetStartEm = parent.insetStartEm + insetStart,
        insetEndEm = parent.insetEndEm + insetEnd,
    )
}

/**
 * 이 요소 자신이 선언한 글꼴 크기를 부모의 것을 기준으로 해석하거나, 선언이 없으면 부모의 값을 사용한다.
 * CSS가 정의한 복합 방식: `0.8em` 안의 `0.8em`은 `0.64`이고 `0.8`이 아니다.
 */
private fun CssLength?.resolveFontScale(parentScale: Float): Float = when (this) {
    is CssLength.Em -> (value * parentScale).takeIf { it > 0f } ?: parentScale
    is CssLength.Percent -> (fraction * parentScale).takeIf { it > 0f } ?: parentScale
    is CssLength.Px -> (value / CssDefaultFontPx).takeIf { it > 0f } ?: parentScale
    null -> parentScale
}

/** 기준 em 단위의 `line-height` 길이: `em`/`%`는 요소 자신의 크기 기준, `px`는 16 기준. */
private fun CssLength.toResolvedLineHeightEm(fontScale: Float): Float? = when (this) {
    is CssLength.Em -> (value * fontScale).takeIf { it > 0f }
    is CssLength.Percent -> (fraction * fontScale).takeIf { it > 0f }
    is CssLength.Px -> (value / CssDefaultFontPx).takeIf { it > 0f }
}

/** 기준 em 단위의 `text-indent`. 퍼센트는 이 파서가 갖지 않은 너비가 필요하므로 무시된다. */
private fun CssLength.toResolvedIndentEm(fontScale: Float): Float? = when (this) {
    is CssLength.Em -> value * fontScale
    is CssLength.Px -> value / CssDefaultFontPx
    is CssLength.Percent -> null
}

/**
 * 기준 em 단위의 마진 또는 패딩 한 변. 음수가 될 수 없다.
 *
 * 음수 마진은 반대 방향으로 콘텐츠를 당기는데 이 렌더러는 그릴 방법이 없으므로 마진 없음으로 읽힌다.
 * 퍼센트는 CSS에서 컨테이닝 블록의 *너비*를 기준으로 해석되는데, 이 파서에는 그 값이 없으므로
 * 정확히 0인 경우만 살아남는다.
 */
private fun CssLength.toResolvedMarginEm(fontScale: Float): Float? = when (this) {
    is CssLength.Em -> (value * fontScale).coerceAtLeast(0f)
    is CssLength.Px -> (value / CssDefaultFontPx).coerceAtLeast(0f)
    is CssLength.Percent -> 0f.takeIf { fraction == 0f }
}

/**
 * [parseXhtmlContent]의 출력을 태그 또는 텍스트 런 하나씩 누적한다: 평탄화된 텍스트,
 * 그것을 가리키는 [ReaderBlock]들, 그리고 도중에 발견된 앵커들. 여러 호출에 걸쳐 유지되는 상태 —
 * 현재 열려 있는 블록, 아직 열린 인라인 스팬과 컨테이너 요소들, 리스트 및 테이블 위치 — 는
 * [parseXhtmlContent] 자체가 아닌 여기에 보관된다. 그래야 그 함수의 단일 순방향 스캔이
 * 재귀 하강이 아닌 태그 경계 위의 평탄한 루프로 유지될 수 있다.
 */
private class XhtmlContentBuilder(
    /**
     * 빌더의 텍스트가 시작하는 절대 오프셋; [parseXhtmlContent]의 `baseOffset` 파라미터 참조.
     */
    private val baseOffset: Long,
    /**
     * 원시 `src`/`href`를 컨테이너 경로로 매핑하거나, 이미지를 버려야 하면 null 반환;
     * [parseXhtmlContent]의 같은 이름 파라미터 참조.
     */
    private val resolveImageHref: (String) -> String?,
    /** 챕터의 전체 CSS 캐스케이드; [parseXhtmlContent]의 같은 이름 파라미터 참조. */
    private val css: EpubCss,
) {
    private val text = StringBuilder()

    /**
     * 지금까지 기록된 모든 [ReaderBlock]. 각 블록이나 독립 요소가 닫힐 때 [flushBlock],
     * [appendImage], [emitStandaloneBlock]이 추가한다. 기록 순서로 남겨지며 —
     * 인라인 그림은 단락이 닫히기 전, 단락 중간에 기록된다 — [build]에서 한 번만 읽기 순서로 정렬된다.
     */
    private val blocks = mutableListOf<ReaderBlock>()

    /**
     * 지금까지 발견된 모든 `id`/`name`/`xml:id`를 [text] 내의 절대 오프셋에 매핑.
     * 마크업을 스캔하면서 [rememberAnchors]가 기록하고, [build]가 정렬하지 않고 그대로 반환한다.
     */
    private val anchors = linkedMapOf<String, Long>()

    /** 현재 빌드 중인 블록이 시작하는 [text] 내 오프셋. 열린 블록이 없으면 -1. */
    private var blockStart = -1

    /** 현재 빌드 중인 블록이 기록될 [ReaderBlockKind]. */
    private var blockKind = ReaderBlockKind.PARAGRAPH

    /** 제목 레벨, 또는 목록 항목이면 목록 중첩 깊이; 그 외는 0. */
    private var blockLevel = 0

    /** 마크업 또는 스타일시트 캐스케이드에서 가져온 현재 빌드 중인 블록의 정렬. */
    private var blockAlign: ReaderTextAlign? = null

    /** 현재 블록이 순서 있는 목록 항목일 때의 서수 마커 텍스트(예: `"3."`). */
    private var blockLabel: String? = null

    /**
     * 스타일시트 캐스케이드가 현재 블록에 부여하는 스타일(글꼴 배율, 굵기, 패밀리, 줄 높이, 들여쓰기).
     */
    private var blockStyle: ReaderBlockStyle? = null

    /** 현재 블록의 해석된 스타일. 인라인 스팬이 이것을 기준으로 델타로 방출될 수 있도록 보관한다. */
    private var blockComputed: ComputedStyle? = null

    /**
     * 현재 블록 뒤에 전체 빈 줄이 오는지, 아니면 단순 줄바꿈 하나만 오는지 여부; [flushBlock] 참조.
     */
    private var blockSeparatesWithBlankLine = true

    /** 현재 블록이 표 셀일 때의 표 행 인덱스. */
    private var blockTableRow: Int? = null

    /** 현재 블록이 표 셀일 때의 표 열 인덱스. */
    private var blockTableColumn: Int? = null

    /** 현재 빌드 중인 블록을 위해 지금까지 수집된 인라인 스팬(굵기, 이탤릭, 링크 등). */
    private val blockSpans = mutableListOf<ReaderSpan>()

    /** 아직 열려 있는 인라인 스타일 요소(`<b>`, `<a>` 등), 가장 안쪽이 마지막. */
    private val openInline = mutableListOf<OpenSpan>()

    /**
     * 아직 열려 있는 블록 및 컨테이너 요소들, 가장 바깥쪽부터 — CSS 조회와 이미지 너비 조회가
     * 모두 순회하는 계층.
     */
    private val openBlocks = mutableListOf<OpenElement>()
    private val openContainers = mutableListOf<OpenContainer>()
    private val hiddenElements = mutableListOf<String>()

    /** 열려 있는 `<ol>`/`<ul>` 컨텍스트들, 가장 안쪽이 마지막. 서수 위치를 추적한다. */
    private val lists = mutableListOf<ListContext>()

    /** 열려 있는 `<table>` 컨텍스트들, 가장 안쪽이 마지막. 행/열 위치를 추적한다. */
    private val tables = mutableListOf<TableContext>()

    /** 중첩된 `<pre>` 요소의 깊이. 0보다 크면 공백이 그대로 기록된다. */
    private var preformattedDepth = 0

    /**
     * 공백 런이 감지됐지만 아직 기록되지 않은 상태. 마크업 패딩이 블록을 열거나
     * 줄의 맨 앞에 오면 안 되기 때문이다.
     */
    private var pendingSpace = false

    /**
     * 첫 번째 제목의 `title` 속성에서 가져온 챕터 이름(있으면);
     * [XhtmlContent.headingTitle]이 된다.
     */
    private var headingTitle: String? = null

    /** 빌드 중인 블록에 기록된 그림 수. 그림만 담은 래퍼를 인식하는 데 사용된다. */
    private var blockImageCount = 0

    /** 빌드 중인 블록에 기록된 명시적 `<br>` 수. 빈 줄 단락을 인식하는 데 사용된다. */
    private var blockLineBreakCount = 0

    private val suppressingHiddenContent: Boolean
        get() = hiddenElements.isNotEmpty()

    /**
     * 태그 사이의 텍스트 런 하나를 출력에 추가한다. 엔티티를 먼저 디코딩한다.
     *
     * `<pre>` 바깥에서는 HTML이 공백을 축약하는 방식으로 축약한다: 공백 런은 즉시 기록하지 않고
     * [pendingSpace]에 보관하는데, 태그 주변의 마크업 패딩이 블록을 열거나 줄을 시작해서는 안 되기
     * 때문이다. 반면 두 인라인 요소 사이의 진짜 공백은 자체 텍스트 런으로 도착하므로 그 간격을
     * 살아남아야 한다. `<pre>` 안([preformattedDepth]로 추적)에서는 텍스트를 그대로, 축약 없이 기록한다.
     *
     * @param rawText 마크업에 그대로 나타난 텍스트 런. 아직 엔티티 디코딩이 안 된 상태.
     */
    fun appendText(rawText: String) {
        if (suppressingHiddenContent) return
        if (rawText.isEmpty()) return
        val decoded = decodeXmlEntities(rawText)
        if (preformattedDepth > 0) {
            ensureBlockOpen()
            pendingSpace = false
            text.append(decoded)
            return
        }
        decoded.forEach { char ->
            if (char.isWhitespace()) {
                pendingSpace = true
                return@forEach
            }
            if (blockStart < 0) ensureBlockOpen()
            if (pendingSpace && text.length > blockStart) text.append(' ')
            pendingSpace = false
            text.append(char)
        }
    }

    /**
     * 열리는(또는 자체 닫히는) 태그 하나를 처리한다. 일부 태그는 즉시 동작하고(`<br>`, `<img>`/`<image>`,
     * `<hr>`), 일부는 리스트/테이블 추적 상태만 쌓으며, 나머지 — 인식된 블록 요소 또는 인라인 스타일 —
     * 는 스팬을 열거나 이전 블록을 닫고 스타일시트 캐스케이드와 마크업 속성이 지시하는 형태로 새 블록을 연다.
     *
     * 이미지의 경우, 너비와 float는 요소가 받는 캐스케이드와 동일하게 해석된다:
     * [css]를 통한 링크된 CSS, 그 위에 덧씌워진 인라인 `style`, 그리고 오래된 클래스 키 기반 이미지
     * 규칙을 위한 레거시 너비 폴백으로 유지되는 [styleSheet]. 인식된 블록 요소의 경우
     * [css]는 요소 자체만이 아닌 전체 열린 요소 계층에 적용되는 선언들을 제공하므로,
     * 래퍼에 설정된 상속된 `text-align`이 중첩된 단락까지 도달한다; 요소에 직접 작성된 마크업
     * (`align` 속성, 인라인 `style`)은 브라우저에서와 마찬가지로 여전히 캐스케이드보다 우선한다.
     * 인라인 스타일 요소가 스팬 시작 위치를 기록하기 전에 대기 중인 공백을 먼저 플러시한다 —
     * 플러시 전에 시작을 기록하면 모든 스팬이 한 문자 일찍 시작하여 감싸는 내용의 선행 공백을 삼켜버렸다.
     *
     * @param tag [parseTag]가 이미 파싱한 열린 태그.
     */
    fun openElement(tag: XhtmlTag) {
        if (suppressingHiddenContent) {
            if (!tag.isSelfClosing) hiddenElements += tag.name
            return
        }
        rememberAnchors(tag.attributes)
        val currentElement = openElementFor(tag)
        if (currentElement.computed.declarations.display == "none") {
            if (!tag.isSelfClosing) hiddenElements += tag.name
            return
        }
        when (tag.name) {
            "br" -> {
                ensureBlockOpen()
                pendingSpace = false
                text.append('\n')
                blockLineBreakCount += 1
                return
            }

            "img", "image" -> {
                val source = tag.attributes["src"] ?: tag.attributes["xlink:href"] ?: tag.attributes["href"]
                val href = source?.let(resolveImageHref)
                if (href != null) {
                    val imageLayout = resolveImageLayout(currentElement, openBlocks)
                    appendImage(
                        imageHref = href,
                        label = tag.attributes["alt"]?.takeIf { it.isNotBlank() },
                        aspectRatio = tag.attributes.declaredImageAspectRatio(),
                        widthPercent = imageLayout.widthPercent,
                        widthEm = imageLayout.widthEm,
                        align = imageLayout.align,
                        float = imageLayout.float,
                        style = imageLayout.style,
                    )
                }
                return
            }

            "hr" -> {
                emitStandaloneBlock(kind = ReaderBlockKind.SEPARATOR)
                return
            }

            "ol", "ul" -> {
                lists += ListContext(isOrdered = tag.name == "ol", nextOrdinal = tag.attributes.startOrdinal())
                openBlocks += currentElement
                return
            }

            "table" -> {
                tables += TableContext()
                openBlocks += currentElement
                return
            }

            "tr" -> {
                tables.lastOrNull()?.let { table ->
                    table.rowIndex += 1
                    table.columnIndex = -1
                }
                openBlocks += currentElement
                return
            }
        }

        InlineStyles[tag.name]?.let { style ->
            val href = tag.attributes["href"]
            if (style == ReaderInlineStyle.LINK && href.isNullOrBlank()) return
            ensureBlockOpen()
            flushPendingSpace()
            val inlineCssStyle = currentElement.computed.toSpanDelta(spanDeltaBase(), css)
            openInline += OpenSpan(
                name = tag.name,
                style = style,
                href = href,
                start = text.length,
                styleDelta = inlineCssStyle,
                computed = currentElement.computed,
            )
            return
        }

        if (BlockKinds[tag.name] == ReaderBlockKind.HEADING && headingTitle == null) {
            headingTitle = tag.attributes["title"]?.trim()?.takeIf(String::isNotEmpty)
        }

        val kind = BlockKinds[tag.name] ?: run {
            if (tag.name in NeutralContainers) {
                // 순수 인라인 컨테이너의 스타일은 스팬으로 텍스트에 전달되고, 블록 레벨 래퍼의 스타일은
                // 내부에서 해석된 모든 블록에 구워 넣어지므로, 전자만 스팬이 필요하다.
                if (tag.name in PureInlineContainers) {
                    ensureBlockOpen()
                    val delta = currentElement.computed.toSpanDelta(spanDeltaBase(), css)
                    openBlocks += currentElement
                    if (delta != null) {
                        flushPendingSpace()
                        openInline += OpenSpan(
                            name = tag.name,
                            style = null,
                            href = null,
                            start = text.length,
                            styleDelta = delta,
                            computed = currentElement.computed,
                        )
                    }
                } else {
                    openBlocks += currentElement
                    maybeOpenContainer(tag.name, currentElement.computed.toReaderBlockStyle(css))
                }
            }
            return
        }

        flushBlock()
        openBlocks += currentElement
        maybeOpenContainer(tag.name, currentElement.computed.toReaderBlockStyle(css))
        blockComputed = currentElement.computed
        blockStyle = currentElement.computed.toReaderBlockStyle(css)
        blockSeparatesWithBlankLine = currentElement.computed.separatesParagraphs()
        blockKind = kind
        blockLevel = when {
            kind == ReaderBlockKind.HEADING -> tag.name.removePrefix("h").toIntOrNull() ?: 1
            kind == ReaderBlockKind.LIST_ITEM -> lists.size.coerceAtLeast(1)
            else -> 0
        }
        blockAlign = tag.attributes.textAlign() ?: currentElement.computed.declarations.textAlign?.toReaderTextAlign()
        blockLabel = null
        if (kind == ReaderBlockKind.LIST_ITEM) {
            lists.lastOrNull()?.let { list ->
                if (list.isOrdered) {
                    blockLabel = "${list.nextOrdinal}."
                    list.nextOrdinal += 1
                }
            }
        }
        if (kind.isTableCellKind()) {
            tables.lastOrNull()?.let { table ->
                table.columnIndex += 1
                blockTableRow = table.rowIndex.coerceAtLeast(0)
                blockTableColumn = table.columnIndex
            }
        }
        if (kind == ReaderBlockKind.PREFORMATTED) preformattedDepth += 1
        ensureBlockOpen()
    }

    /**
     * 닫는 태그 하나를 처리한다. 이 이름의 가장 안쪽에 열려 있는 인라인 스팬이 있으면 닫고,
     * 리스트/테이블 추적을 팝하며, 이 이름의 가장 안쪽에 열려 있는 블록 요소가 있으면 닫는다(플러시 포함).
     * 매칭되는 열린 요소가 없는 닫는 태그 — 조상의 자체 닫기로 이미 소비됐거나,
     * 매칭 열린 태그가 전혀 없는 잘못된 마크업 — 는 단순히 무시한다.
     *
     * @param name 닫는 태그의 요소 이름(호출자가 이미 소문자로 변환한 것).
     */
    fun closeElement(name: String) {
        val lowered = name.lowercase()
        if (hiddenElements.isNotEmpty()) {
            hiddenElements.indexOfLast { it == lowered }.takeIf { it >= 0 }?.let { hiddenElements.removeAt(it) }
            return
        }
        openInline.indexOfLast { it.name == lowered }.takeIf { it >= 0 }?.let { spanIndex ->
            val span = openInline.removeAt(spanIndex)
            if (text.length > span.start) {
                blockSpans += ReaderSpan(
                    range = TextRange(baseOffset + span.start, baseOffset + text.length),
                    style = span.style,
                    href = span.href,
                    styleDelta = span.styleDelta?.takeIf { !it.isEmpty() },
                )
            }
        }

        when (lowered) {
            "ol", "ul" -> lists.removeLastOrNull()
            "table" -> tables.removeLastOrNull()
        }
        if (lowered == "pre" && preformattedDepth > 0) preformattedDepth -= 1

        val blockIndex = openBlocks.indexOfLast { it.name == lowered }
        if (blockIndex >= 0) {
            openBlocks.removeAt(blockIndex)
            if (lowered in BlockKinds) flushBlock()
        }
        closeContainer(lowered)
    }

    /**
     * 챕터를 마무리한다: 아직 열려 있는 블록을 플러시하고, 닫는 블록이 가독 콘텐츠 끝 이후에
     * 남겨두는 후행 빈 줄을 잘라내며, 누적된 [XhtmlContent]를 반환한다.
     *
     * 잘라내기는 텍스트 맨 끝이 아닌 마지막으로 기록된 블록 범위의 끝에서 멈춘다. 그 블록 뒤에
     * 기록된 구분자가 바로 챕터 끝 후행 공백으로 나타날 빈 줄이기 때문이다. 반환되는 블록들은
     * 기록된 순서가 아닌 시작 오프셋 기준으로 정렬된다. 인라인 그림은 기록되는 순간 — 단락 중간 —
     * 저장되는 반면, 그것을 감싸는 단락은 그 단락 자체의 블록이 닫힐 때만 기록되기 때문이다;
     * 여기서 목록을 읽기 순서로 되돌리는 것이 하위 소비자들이 블록이 이미 그 순서로 왔다고
     * 가정할 수 있게 해준다.
     *
     * @return 이 챕터의 누적된 텍스트, 블록(읽기 순서), 앵커, 제목 타이틀.
     */
    fun build(): XhtmlContent {
        flushBlock()
        while (openContainers.isNotEmpty()) closeContainer(openContainers.last().name)
        val minimumLength = blocks.maxOfOrNull { block -> (block.range.end - baseOffset).toInt() } ?: 0
        while (text.length > minimumLength && text.isNotEmpty() && text.last() == '\n') {
            text.deleteAt(text.length - 1)
        }
        return XhtmlContent(
            text = text.toString(),
            blocks = blocks.sortedBy { block -> block.range.start },
            anchors = anchors.toMap(),
            headingTitle = headingTitle,
        )
    }

    /**
     * [attributes]의 `id`/`name`/`xml:id`가 있으면 현재 기록 위치를 가리키는 것으로 등록한다.
     * 같은 이름에 대해 먼저 기록된 앵커가 이후 중복보다 우선한다.
     */
    private fun rememberAnchors(attributes: Map<String, String>) {
        val absoluteOffset = baseOffset + text.length
        listOfNotNull(attributes["id"], attributes["name"], attributes["xml:id"])
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { anchor -> if (anchor !in anchors) anchors[anchor] = absoluteOffset }
    }

    /**
     * 아직 열린 블록이 없으면 현재 기록 위치에서 블록이 시작됨으로 표시한다.
     *
     * 블록 태그가 아닌 여기서 열리는 블록 — `<body>`나 래퍼 안에 직접 설정된 텍스트 — 은
     * 조상들이 해석한 스타일 안에 여전히 위치하므로, 가장 가까운 블록 레벨 조상의 계산된 스타일에서
     * 상속된 시각적 부분만 가져온다. 순수 인라인 컨테이너는 그 조상으로서 건너뛴다:
     * 그것의 스타일은 스팬으로 텍스트에 전달되며, 여기서도 가져오면 두 번 적용된다.
     */
    private fun ensureBlockOpen() {
        if (blockStart >= 0) return
        blockStart = text.length
        if (blockStyle == null) {
            openBlocks.lastOrNull { it.name !in PureInlineContainers }?.computed?.let { context ->
                blockComputed = context
                blockStyle = context.toInheritedReaderBlockStyle(css)
            }
        }
    }

    /** 스타일을 가장 안쪽 열린 조상에서 점진적으로 해석하여 [OpenElement] 하나를 빌드한다. */
    private fun openElementFor(tag: XhtmlTag): OpenElement {
        val classNames = tag.classNames()
        val id = tag.attributes["id"]
        val inline = tag.attributes.inlineCssDeclarations()
        val ancestry = openBlocks.map(OpenElement::cssElement) + CssElement(tag = tag.name, classes = classNames.toSet(), id = id)
        val computed = resolveComputedStyle(
            parent = openBlocks.lastOrNull()?.computed ?: ComputedStyle.Root,
            css = css,
            ancestry = ancestry,
            inline = inline,
            accumulatesInset = tag.name.accumulatesInset(),
        )
        return OpenElement(tag.name, classNames, id, inline, computed)
    }

    /** 다음 인라인 스팬이 델타를 계산할 기준 스타일: 가장 안쪽 스팬, 없으면 블록. */
    private fun spanDeltaBase(): ComputedStyle =
        openInline.lastOrNull()?.computed
            ?: blockComputed
            ?: openBlocks.lastOrNull()?.computed
            ?: ComputedStyle.Root

    /**
     * [pendingSpace]에 보류된 공백이 있으면 기록한다. 단, 열린 블록의 첫 번째 문자가 되는 경우는 제외한다.
     */
    private fun flushPendingSpace() {
        if (!pendingSpace) return
        if (blockStart >= 0 && text.length > blockStart) text.append(' ')
        pendingSpace = false
    }

    /**
     * 그림을 기록된 위치, 즉 보통은 두 블록 사이가 아닌 빌드 중인 줄 안에 기록한다.
     *
     * HTML에서 `<img>`는 인라인 콘텐츠이므로, 일반 인라인 글리프나 아이콘은 단락 안에 머문다.
     * 플로트된 이미지도 마찬가지로 인라인으로 보존된다; 퍼블리셔의 float 자체는 나중에 렌더러가
     * 결정할 수 있도록 이미지 블록에 실린다. 블록에서 유일한 내용이 그림으로 판명된 경우는
     * [flushBlock]에서 여전히 인식되어, 판화처럼 자체 줄에 단독으로 위치한다.
     */
    private fun appendImage(
        imageHref: String,
        label: String?,
        aspectRatio: Float?,
        widthPercent: Float?,
        widthEm: Float?,
        align: ReaderTextAlign = ReaderTextAlign.CENTER,
        float: ReaderFloat? = null,
        style: ReaderBlockStyle? = null,
    ) {
        ensureBlockOpen()
        flushPendingSpace()
        val start = text.length
        text.append(ReaderObjectReplacementChar)
        blockImageCount += 1
        blocks += ReaderBlock(
            kind = ReaderBlockKind.IMAGE,
            range = TextRange(baseOffset + start, baseOffset + text.length),
            imageHref = imageHref,
            label = label,
            align = align,
            imageAspectRatio = aspectRatio,
            imageWidthPercent = widthPercent,
            imageWidthEm = widthEm,
            float = float,
            style = style?.takeIf { !it.isEmpty() },
        )
    }

    /**
     * 주변 텍스트에서 만들어지지 않는 독립 블록 — 현재는 규칙선(`<hr>`)만 해당 — 을
     * 한 문자와 자신의 [ReaderBlock]으로 기록하되, 이미 열려 있던 단락을 먼저 플러시한다.
     *
     * 블록에는 너비 0이 아닌 실제 1문자 범위를 부여한다. 너비 0 범위는 페이지 경계에서
     * 페이지 범위 필터를 통과해버리기 때문이다. 뒤에 기록된 단일 줄바꿈은 블록 자체의 줄을 끝낸다;
     * 앞에는 빈 줄을 추가하지 않는데, 앞의 단락 자체 플러시가 이미 하나를 기록했기 때문이다 —
     * 브라우저가 `<hr>`에 주는 것과 동일한 간격이며, 양쪽에서 각각 줄바꿈이 오면 생기는
     * 두 배 가량의 간격을 피한다.
     *
     * @param kind 기록할 블록 종류.
     */
    private fun emitStandaloneBlock(kind: ReaderBlockKind) {
        flushBlock()
        pendingSpace = false
        val start = text.length
        text.append(ReaderObjectReplacementChar)
        blocks += ReaderBlock(
            kind = kind,
            range = TextRange(baseOffset + start, baseOffset + text.length),
        )
        text.append('\n')
    }

    /**
     * 현재 열려 있는 블록(있으면)을 닫고 그 뒤에 구분자를 기록한다.
     *
     * 후행 블록 패딩(공백, 탭, 줄바꿈)을 먼저 끝에서 잘라낸다; 잘라낸 뒤 완전히 비어있는 블록 —
     * 시작과 끝 오프셋이 같아진 경우 — 은 [ReaderBlock]을 전혀 기록하지 않으며, 열린 스팬은
     * 빈 범위를 위해 보관하지 않고 버린다. 그림만 담은 블록(예: 이미지 전용 래퍼 `<div>`)도
     * 동일하게 처리한다: 기록할 산문이 없으므로 단락 블록은 기록되지 않으며, 이미 기록된 각 그림은
     * 빈 단락 안에 접혀 들어가지 않고 자체 줄을 유지한다. 그 외에는 열려 있는 동안 설정된 종류, 레벨,
     * 스팬, 정렬, 레이블, 표 위치, 스타일로 [ReaderBlock]이 기록된다. 뒤에 기록되는 구분자는
     * [blockSeparatesWithBlankLine]에 따라 빈 줄(`"\n\n"`) 또는 단일 줄바꿈(`"\n"`) —
     * 페이지에서 하나의 단락이 다음 단락과 구별되는 방식이다. 단락에 `margin: 0` 스타일시트 규칙은
     * 이 리더가 단락들이 간격 없이 이어져야 한다는 신호로 해석한다(대신 첫 줄 들여쓰기로 구분된다);
     * 그럼에도 빈 줄을 주면 책이 의도한 것의 약 두 배 길이로 페이지가 펼쳐진다.
     */
    private fun flushBlock() {
        val start = blockStart
        val separatesWithBlankLine = blockSeparatesWithBlankLine
        pendingSpace = false
        resetOpenSpans()
        val imageCount = blockImageCount
        blockImageCount = 0
        val lineBreakCount = blockLineBreakCount
        blockLineBreakCount = 0
        if (start < 0) {
            resetBlockAttributes()
            return
        }
        blockStart = -1
        while (text.length > start && text.last().isBlockPadding()) text.deleteAt(text.length - 1)
        if (text.length == start) {
            // 명시적 줄바꿈만 담은 단락은 빈 단락이 아닌 *빈 줄* 단락이다: `<p><br/></p>`는
            // 브라우저에서 빈 줄 하나로 그려지며, 이 책들이 챕터 제목 박스와 본문 사이 공간을
            // 표현하는 방식이다. 이것을 버리면 둘이 붙어버린다. 줄바꿈은 단락 자체의 콘텐츠로
            // 유지하므로 각각이 단락의 줄 높이 한 줄로 그려지며, 그 높이는 책에서 해당 공간에
            // 설정한 높이와 정확히 일치한다.
            if (lineBreakCount > 0) {
                repeat(lineBreakCount) { text.append('\n') }
            } else {
                blockSpans.clear()
                resetBlockAttributes()
                return
            }
        }

        if (imageCount > 0 && text.substring(start, text.length).isBlankIgnoringObjects()) {
            blockSpans.clear()
            resetBlockAttributes()
            text.append('\n')
            return
        }

        blocks += ReaderBlock(
            kind = blockKind,
            range = TextRange(baseOffset + start, baseOffset + text.length),
            level = blockLevel,
            spans = blockSpans.filter { span -> span.range.start < baseOffset + text.length }.toList(),
            align = blockAlign,
            label = blockLabel,
            tableRow = blockTableRow,
            tableColumn = blockTableColumn,
            style = blockStyle?.takeIf { !it.isEmpty() },
        )
        blockSpans.clear()
        resetBlockAttributes()
        text.append(if (separatesWithBlankLine) "\n\n" else "\n")
    }

    /**
     * 블록 자체의 끝에서 아직 열린 모든 인라인 스팬을 닫는다. 그래야 닫히지 않은 인라인 마크업
     * (예: 종료되지 않은 `<b>`)이 이후 내용으로 누수되지 않고 블록과 함께 끝난다.
     */
    private fun resetOpenSpans() {
        if (openInline.isEmpty()) return
        openInline.asReversed().forEach { span ->
            if (text.length > span.start) {
                blockSpans += ReaderSpan(
                    range = TextRange(baseOffset + span.start, baseOffset + text.length),
                    style = span.style,
                    href = span.href,
                    styleDelta = span.styleDelta?.takeIf { !it.isEmpty() },
                )
            }
        }
        openInline.clear()
    }

    /** 모든 블록별 상태를 기본값으로 재설정하여 다음 블록을 빌드할 준비를 한다. */
    private fun resetBlockAttributes() {
        blockKind = ReaderBlockKind.PARAGRAPH
        blockLevel = 0
        blockAlign = null
        blockLabel = null
        blockTableRow = null
        blockTableColumn = null
        blockStyle = null
        blockComputed = null
        blockSeparatesWithBlankLine = true
    }

    private fun maybeOpenContainer(name: String, style: ReaderBlockStyle?) {
        if (style == null || style.isEmpty() || !style.hasVisualContainerData() || name in PureInlineContainers) return
        openContainers += OpenContainer(
            name = name,
            start = text.length,
            style = style,
            depth = openContainers.size + 1,
            isPageContainer = name == "html" || name == "body",
        )
    }

    private fun closeContainer(name: String) {
        val index = openContainers.indexOfLast { it.name == name }
        if (index < 0) return
        val container = openContainers.removeAt(index)
        val start = baseOffset + container.start
        var endIndex = text.length
        while (endIndex > container.start && text[endIndex - 1].isBlockPadding()) endIndex -= 1
        val end = baseOffset + endIndex
        if (end <= start) return
        // 정확히 텍스트 런 하나를 감싼 스타일 있는 블록 요소는 이미 그 리프 블록에
        // 전체 스타일을 갖고 있다 — 그것의 박스가 리프의 박스다. 같은 범위에 같은 스타일로
        // CONTAINER 쌍둥이를 기록하면 모든 렌더러가 간격 이중 계산을 피하기 위해 범위와 스타일을
        // 비교해서 중복을 재발견해야 했다; 쌍둥이가 생성될 단 하나의 지점에서 억제하면
        // 불변성이 구조적으로 유지된다: CONTAINER는 항상 진짜 래퍼다.
        // 페이지 컨테이너는 예외 — html/body는 항상 기록해야 하며, 페이지 마진과
        // 페이지 배경이 여기서 읽히기 때문이다.
        val range = TextRange(start, end)
        if (!container.isPageContainer &&
            blocks.any { block ->
                block.kind != ReaderBlockKind.CONTAINER && block.range == range && block.style == container.style
            }
        ) {
            return
        }
        val block = ReaderBlock(
            kind = ReaderBlockKind.CONTAINER,
            range = range,
            level = container.depth,
            style = container.style.takeIf { !it.isEmpty() },
            isPageContainer = container.isPageContainer,
        )
        if (blocks.lastOrNull() != block) blocks += block
    }

    private data class OpenContainer(
        val name: String,
        val start: Int,
        val style: ReaderBlockStyle,
        val depth: Int,
        val isPageContainer: Boolean,
    )
}

/**
 * 현재 열려 있는 인라인 스타일 요소(`<b>`, `<a>` 등). 닫는 태그가 이것을 [ReaderSpan]으로
 * 변환할 때까지 기억된다.
 */
private class OpenSpan(
    /** 소문자로 변환된 태그 이름. 이 스팬을 끝내는 닫는 태그와 매칭된다. */
    val name: String,
    /** 이 요소가 닫힐 때 적용하는 인라인 스타일. */
    val style: ReaderInlineStyle?,
    /** [ReaderInlineStyle.LINK]의 링크 대상; 그 외는 null. */
    val href: String?,
    /** 이 스팬이 시작하는 빌더 텍스트 내 오프셋. */
    val start: Int,
    /** 이 스팬이 담고 있는 CSS 유래 추가 스타일. 감싸는 컨텍스트에 대한 델타. */
    val styleDelta: ReaderSpanStyle? = null,
    /** 요소의 해석된 스타일. 이 스팬 안에 중첩된 스팬의 델타 기준. */
    val computed: ComputedStyle = ComputedStyle.Root,
)

/** 열려 있는 `<ol>`/`<ul>` 하나의 위치를 추적한다. */
private class ListContext(
    /** `<ol>`이면 true, `<ul>`이면 false — 순서 있는 목록만 항목에 숫자 레이블을 붙인다. */
    val isOrdered: Boolean,
    /** 다음 `<li>`에 붙일 서수. 항목마다 하나씩 증가한다. */
    var nextOrdinal: Int,
)

/** 열려 있는 `<table>` 하나의 위치를 추적한다. */
private class TableContext {
    /** 현재 열려 있는 행의 인덱스. `<tr>`마다 증가하며, 첫 행 전은 -1. */
    var rowIndex: Int = -1

    /**
     * 행 안에서 현재 열려 있는 셀의 인덱스. `<td>`/`<th>`마다 증가하며, 각 `<tr>`에서 -1로 초기화된다.
     */
    var columnIndex: Int = -1
}

/** 이 문자가 [XhtmlContentBuilder]가 블록의 후행 끝에서 잘라내는 공백인지 여부. */
private fun Char.isBlockPadding(): Boolean = this == ' ' || this == '\n' || this == '\t' || this == '\r'

/**
 * 이 종류가 행/열 위치를 갖는 두 표 셀 종류 중 하나인지 여부.
 */
private fun ReaderBlockKind.isTableCellKind(): Boolean =
    this == ReaderBlockKind.TABLE_CELL || this == ReaderBlockKind.TABLE_HEADER_CELL

/** 이 태그의 `class` 속성을 공백으로 분할한 개별 클래스 이름 목록. */
private fun XhtmlTag.classNames(): List<String> =
    attributes["class"].orEmpty().split(WhitespaceRunRegex).map(String::trim).filter(String::isNotEmpty)

/** `<ol>`의 `start` 속성. 항목에 붙일 첫 번째 서수이며, 기본값은 1. */
private fun Map<String, String>.startOrdinal(): Int = this["start"]?.toIntOrNull() ?: 1

/** 이 태그의 인라인 `style` 선언. 링크된 CSS와 동일한 선언 파서로 파싱된다. */
private fun Map<String, String>.inlineCssDeclarations(): CssDeclarations =
    this["style"]?.let(::parseCssDeclarations) ?: CssDeclarations.Empty

/**
 * 이 요소 자신의 인라인-스타트/엔드 마진과 패딩이 자식들의 레이아웃 인셋에 합산되는지 여부.
 * 블록 레벨 래퍼와 블록은 합산된다; 인라인 요소는 그렇지 않다; `html`/`body`도 그렇지 않다,
 * 그것들의 간격은 단락별 인셋이 아닌 리더의 페이지 마진이 되기 때문이다.
 */
private fun String.accumulatesInset(): Boolean = when {
    this == "html" || this == "body" -> false
    this in PureInlineContainers -> false
    this in BlockKinds -> true
    this in NeutralContainers -> true
    this == "ol" || this == "ul" || this == "table" || this == "tr" -> true
    else -> false
}

private fun CssLength.toCssWidthOrNull(): CssWidth? = when (this) {
    is CssLength.Percent -> fraction.takeIf { it > 0f }?.let(CssWidth::Percent)
    is CssLength.Em -> value.takeIf { it > 0f }?.let(CssWidth::Em)
    else -> null
}

private fun resolveDeclaredImageWidth(ownWidth: CssWidth?, ancestorWidth: CssWidth?): CssWidth? = when (ownWidth) {
    is CssWidth.Em -> ownWidth
    is CssWidth.Percent -> when (ancestorWidth) {
        is CssWidth.Em -> CssWidth.Em(ancestorWidth.value * ownWidth.fraction)
        is CssWidth.Percent -> CssWidth.Percent(ancestorWidth.fraction * ownWidth.fraction)
        null -> ownWidth
    }
    null -> ancestorWidth
}

private fun CssDeclarations.floatOrNull(): ReaderFloat? = when (float) {
    "left" -> ReaderFloat.START
    "right" -> ReaderFloat.END
    else -> null
}

private data class ResolvedImageLayout(
    val widthPercent: Float? = null,
    val widthEm: Float? = null,
    val align: ReaderTextAlign = ReaderTextAlign.CENTER,
    val float: ReaderFloat? = null,
    val style: ReaderBlockStyle? = null,
)

private fun resolveImageLayout(
    current: OpenElement,
    openBlocks: List<OpenElement>,
): ResolvedImageLayout {
    val ownDeclarations = current.computed.declarations
    val ancestorDeclarations = openBlocks.asReversed().map { element -> element.computed.declarations }
    val ancestorWidth = ancestorDeclarations.firstNotNullOfOrNull { it.width?.toCssWidthOrNull() }
    val width = resolveDeclaredImageWidth(
        ownWidth = ownDeclarations.width?.toCssWidthOrNull(),
        ancestorWidth = ancestorWidth,
    )
    val float = (sequenceOf(ownDeclarations) + ancestorDeclarations.asSequence())
        .mapNotNull(CssDeclarations::floatOrNull)
        .firstOrNull()
    // float는 한쪽 가장자리를 차지하며 무조건 우선한다. 그 외에는 의도적 배치 —
    // 이미지에 상속된 `text-align`이 `center` 또는 `right`인 경우 — 가 우선된다;
    // `left`/`justify`는 책이 *산문*(body/p 기본값, 이미지가 그냥 상속받는 것)을 스타일링하는 방식이며,
    // 읽기 시스템은 그 아래에서도 여전히 판화를 중앙에 위치시키므로,
    // 이미지를 마진으로 끌어당기지 않고 CENTER 기본값으로 넘어간다.
    val inheritedAlign = ownDeclarations.textAlign?.toReaderTextAlign()
    val align = float?.toTextAlign()
        ?: inheritedAlign?.takeIf { it == ReaderTextAlign.CENTER || it == ReaderTextAlign.END }
        ?: ReaderTextAlign.CENTER
    return ResolvedImageLayout(
        widthPercent = (width as? CssWidth.Percent)?.fraction,
        widthEm = (width as? CssWidth.Em)?.value,
        align = align,
        float = float,
        style = ownDeclarations.toReaderImageStyle(),
    )
}

/**
 * 스팬의 스타일을 자신의 해석된 스타일과 [base](감싸는 것의 스타일 — 가장 안쪽 열린 스팬, 또는 블록)의
 * *차이*로 표현한다.
 *
 * 델타만이 스팬이 여기서 안전하게 담을 수 있는 유일한 형태다. 렌더러는 Compose가 스팬 스타일을
 * 중첩하는 방식으로 중첩한다: `em` 글꼴 크기는 그 위치에 이미 적용된 크기와 곱해진다. 완전히 상속된
 * 스타일을 갖는 스팬은 블록이 이미 적용한 모든 것을 다시 적용하게 되어 — `0.9em` 래퍼의 텍스트가
 * `0.81`로 나온다. [ReaderSpanStyle]은 이것을 구조적으로 만든다: 스팬에는 절대 길이를 명시조차 할 수 없다.
 *
 * @receiver 스팬 요소의 해석된 스타일.
 * @param base 감싸는 컨텍스트의 해석된 스타일.
 * @return 실제로 다른 속성들, 또는 아무것도 다르지 않으면 null.
 */
private fun ComputedStyle.toSpanDelta(base: ComputedStyle, css: EpubCss): ReaderSpanStyle? {
    fun <T> changed(value: T?, baseValue: T?): T? = value?.takeIf { it != baseValue }
    val own = declarations
    val baseDeclarations = base.declarations
    return ReaderSpanStyle(
        fontScale = (fontScale / base.fontScale).takeIf { ratio -> abs(ratio - 1f) > FontScaleRatioEpsilon },
        bold = changed(own.fontWeight?.toBoldOrNull(), baseDeclarations.fontWeight?.toBoldOrNull()),
        italic = changed(own.fontStyle?.toItalicFlag(), baseDeclarations.fontStyle?.toItalicFlag()),
        fontFamily = changed(own.fontFamily?.toReaderFontFamily(), baseDeclarations.fontFamily?.toReaderFontFamily()),
        fontFamilyName = changed(own.fontFamily?.toPublisherFontFamilyName(), baseDeclarations.fontFamily?.toPublisherFontFamilyName()),
        fontHref = changed(css.resolvedFontHref(own.fontFamily), css.resolvedFontHref(baseDeclarations.fontFamily)),
        foregroundColor = changed(own.color?.toReaderColorOrNull(), baseDeclarations.color?.toReaderColorOrNull()),
        underline = changed(underline, base.underline),
        lineThrough = changed(lineThrough, base.lineThrough),
    ).takeIf { !it.isEmpty() }
}

/** 스팬의 글꼴 배율 비율이 방출할 가치가 있으려면 정확히 1에서 얼마나 멀어야 하는지. */
private const val FontScaleRatioEpsilon = 0.001f

private fun CssDeclarations.toReaderImageStyle(): ReaderBlockStyle? = ReaderBlockStyle(
    foregroundColor = color?.toReaderColorOrNull(),
    boxStyle = toReaderBoxStyle(),
).takeIf { !it.isEmpty() }

/**
 * 마크업이 `width`/`height` 속성 또는 인라인 `style`로 둘 다 순수 픽셀 숫자로 선언한 경우의
 * 너비 나누기 높이. `%`이거나 치수가 없으면 실제 종횡비가 없으므로 추측하지 않고 null로 남긴다;
 * 실제 픽셀은 대신 이미지 바이트에서 읽는다.
 */
private fun Map<String, String>.declaredImageAspectRatio(): Float? {
    val declaredWidth = this["width"]?.toPixelValue() ?: this["style"]?.let { cssPixelDimension(it, CssWidthPxRegex) }
    val declaredHeight = this["height"]?.toPixelValue() ?: this["style"]?.let { cssPixelDimension(it, CssHeightPxRegex) }
    if (declaredWidth == null || declaredHeight == null || declaredWidth <= 0f || declaredHeight <= 0f) return null
    return declaredWidth / declaredHeight
}

/**
 * 이 속성 값을 순수 픽셀 숫자로 반환하거나, 비어 있거나 단위 문자(예: `"100%"`, `"2em"`)를
 * 포함하는 경우 null을 반환한다.
 */
private fun String.toPixelValue(): Float? = trim().takeIf { it.isNotEmpty() && it.none(Char::isLetter) }?.toFloatOrNull()

/**
 * 인라인 `style` 속성에서 [dimension]이 매칭하는 픽셀 값을 반환하거나, 속성이 없거나 `px`로
 * 선언되지 않은 경우 null을 반환한다.
 *
 * 속성 이름 대신 이미 컴파일된 패턴을 받는다. 이미지 페이지 측정 시 `<img>`마다 정규식을
 * 재컴파일하지 않기 위해서다: [CssWidthPxRegex]와 [CssHeightPxRegex]가 어떤 호출자든 요청하는
 * 유일한 두 속성이며, 둘 다 프로세스당 한 번만 빌드된다.
 *
 * @param style 원시 인라인 `style` 속성 텍스트.
 * @param dimension 첫 번째 그룹이 숫자 `px` 값을 캡처하는 패턴, 즉 [CssWidthPxRegex] 또는 [CssHeightPxRegex].
 */
private fun cssPixelDimension(style: String, dimension: Regex): Float? =
    dimension.find(style)?.groupValues?.get(1)?.toFloatOrNull()

/**
 * 이 태그의 `align` 속성 또는 인라인 `style`의 `text-align`에서 가져온 정렬.
 * 둘 다 인식된 값을 선언하지 않으면 null.
 */
private fun Map<String, String>.textAlign(): ReaderTextAlign? {
    val declared = this["align"] ?: this["style"]?.let { style ->
        TextAlignRegex.find(style)?.groupValues?.get(1)
    } ?: return null
    return when (declared.trim().lowercase()) {
        "center" -> ReaderTextAlign.CENTER
        "right", "end" -> ReaderTextAlign.END
        "justify" -> ReaderTextAlign.JUSTIFY
        "left", "start" -> ReaderTextAlign.START
        else -> null
    }
}

/**
 * [value]에 있는 XML/HTML 문자 참조를 디코딩한다: 숫자형(`&#160;`, `&#x1F600;`)과
 * [NamedEntities]에 있는 이름 있는 엔티티. [MaxEntityLength]보다 긴 엔티티 참조, 닫는 `;`가 없는 것,
 * 또는 [NamedEntities]에 없는 엔티티를 이름으로 지정한 것은 버리거나 추측하지 않고 쓰여진 그대로
 * 출력에 남긴다 — 잘못되거나 인식할 수 없는 마크업이 주변 텍스트를 조용히 삼켜버려서는 안 된다.
 *
 * @param value 엔티티 참조가 포함될 수 있는 원시 텍스트.
 * @return 모든 인식된 엔티티가 나타내는 문자(들)로 교체된 [value].
 */
internal fun decodeXmlEntities(value: String): String {
    if ('&' !in value) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '&') {
            out.append(char)
            index += 1
            continue
        }
        val end = value.indexOf(';', index + 1)
        if (end < 0 || end - index > MaxEntityLength) {
            out.append(char)
            index += 1
            continue
        }
        val body = value.substring(index + 1, end)
        val replacement = when {
            body.startsWith("#x", ignoreCase = true) -> body.drop(2).toIntOrNull(16)?.toCharsOrNull()
            body.startsWith("#") -> body.drop(1).toIntOrNull()?.toCharsOrNull()
            else -> NamedEntities[body]
        }
        if (replacement == null) {
            out.append(char)
            index += 1
            continue
        }
        out.append(replacement)
        index = end + 1
    }
    return out.toString()
}

/**
 * 이 유니코드 코드 포인트를 `String`으로 반환한다. BMP 위의 경우 UTF-16 서로게이트 쌍을 사용한다.
 * 범위를 벗어나거나 자체가 쌍 없는 서로게이트 값이면 null.
 */
private fun Int.toCharsOrNull(): String? = when {
    this <= 0 || this > 0x10FFFF -> null
    this in 0xD800..0xDFFF -> null
    this <= 0xFFFF -> toChar().toString()
    else -> {
        val value = this - 0x10000
        charArrayOf(
            (0xD800 + (value shr 10)).toChar(),
            (0xDC00 + (value and 0x3FF)).toChar(),
        ).concatToString()
    }
}

/** 태그 본문 내의 `name="value"` 또는 `name='value'` 속성 쌍 하나와 매칭된다. */
private val TagAttributeRegex = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

/**
 * 공백 런 하나와 매칭된다. `class` 속성을 개별 이름으로 분리하는 데 사용된다.
 *
 * [classNames]에서 끌어올린 이유는, 그것이 파서가 보는 모든 열린 태그마다 실행되기 때문이다:
 * 풀 길이 책은 약 10만 개의 태그를 임포트하며, 태그마다 이 패턴을 컴파일하면 프로세스당 한 번으로
 * 줄일 수 있는 정규식 컴파일 비용을 매번 치르게 된다.
 */
private val WhitespaceRunRegex = Regex("""\s+""")

/** 인라인 `style`의 `width` 선언에서 숫자 `px` 값을 캡처한다; [cssPixelDimension] 참조. */
private val CssWidthPxRegex = Regex("""width\s*:\s*([0-9.]+)px""")

/** 인라인 `style`의 `height` 선언에서 숫자 `px` 값을 캡처한다; [cssPixelDimension] 참조. */
private val CssHeightPxRegex = Regex("""height\s*:\s*([0-9.]+)px""")

/** 인라인 `style` 속성의 `text-align` 값을 캡처한다. */
private val TextAlignRegex = Regex("""text-align\s*:\s*([a-zA-Z]+)""")

/**
 * [decodeXmlEntities]가 해석을 시도하는 가장 긴 엔티티 참조(`&`와 `;` 사이의 이름 또는 숫자).
 * 이를 초과하면 포기하고 `&`를 리터럴 텍스트로 남긴다.
 */
private const val MaxEntityLength = 12

/**
 * 전체 본문을 가독 불가로 건너뛰는 요소 이름들 — 스크립트 코드, CSS 소스 텍스트, 페이지 메타데이터.
 * `svg`는 인식된 블록이나 인라인 태그가 아님에도 불구하고 의도적으로 포함하지 않는다;
 * 동일하게 건너뛰면 왜 안 되는지는 [parseXhtmlContent] 참조.
 */
private val SkippedBodyElements = setOf("script", "style", "head", "title")

/**
 * 인식된 블록([BlockKinds])도 인라인 스타일([InlineStyles])도 아니지만, 여전히 열린 요소 계층에
 * 쌓이는 요소들. 그래야 자신의 클래스나 id가 하위 요소를 대상으로 하는 CSS 규칙(`.quotebox p`)에
 * 매칭될 수 있다. 비록 이것들 중 하나를 여는 것 자체만으로는 새 블록을 시작하지 않더라도.
 */
private val NeutralContainers = setOf(
    "html", "body", "span", "font", "small", "big", "label", "tbody", "thead", "tfoot",
    "colgroup", "col", "nav", "header", "footer", "main", "aside", "figure", "dl",
)

private val PureInlineContainers = setOf("span", "font", "small", "big", "label")

/**
 * 인식된 블록 레벨 요소들을 각각이 되는 [ReaderBlockKind]에 매핑한 것.
 * 그 외는 [NeutralContainers]로 넘어가거나 완전히 무시된다.
 */
private val BlockKinds: Map<String, ReaderBlockKind> = mapOf(
    "p" to ReaderBlockKind.PARAGRAPH,
    "div" to ReaderBlockKind.PARAGRAPH,
    "section" to ReaderBlockKind.PARAGRAPH,
    "article" to ReaderBlockKind.PARAGRAPH,
    "center" to ReaderBlockKind.PARAGRAPH,
    "figcaption" to ReaderBlockKind.PARAGRAPH,
    "dd" to ReaderBlockKind.PARAGRAPH,
    "dt" to ReaderBlockKind.PARAGRAPH,
    "h1" to ReaderBlockKind.HEADING,
    "h2" to ReaderBlockKind.HEADING,
    "h3" to ReaderBlockKind.HEADING,
    "h4" to ReaderBlockKind.HEADING,
    "h5" to ReaderBlockKind.HEADING,
    "h6" to ReaderBlockKind.HEADING,
    "blockquote" to ReaderBlockKind.QUOTE,
    "li" to ReaderBlockKind.LIST_ITEM,
    "pre" to ReaderBlockKind.PREFORMATTED,
    "td" to ReaderBlockKind.TABLE_CELL,
    "th" to ReaderBlockKind.TABLE_HEADER_CELL,
)

/** 인식된 인라인 스타일 요소들을 닫힐 때 각각이 되는 [ReaderInlineStyle] 스팬에 매핑한 것. */
private val InlineStyles: Map<String, ReaderInlineStyle> = mapOf(
    "a" to ReaderInlineStyle.LINK,
    "b" to ReaderInlineStyle.BOLD,
    "strong" to ReaderInlineStyle.BOLD,
    "i" to ReaderInlineStyle.ITALIC,
    "em" to ReaderInlineStyle.ITALIC,
    "cite" to ReaderInlineStyle.ITALIC,
    "dfn" to ReaderInlineStyle.ITALIC,
    "var" to ReaderInlineStyle.ITALIC,
    "u" to ReaderInlineStyle.UNDERLINE,
    "ins" to ReaderInlineStyle.UNDERLINE,
    "s" to ReaderInlineStyle.STRIKETHROUGH,
    "strike" to ReaderInlineStyle.STRIKETHROUGH,
    "del" to ReaderInlineStyle.STRIKETHROUGH,
    "code" to ReaderInlineStyle.MONOSPACE,
    "kbd" to ReaderInlineStyle.MONOSPACE,
    "samp" to ReaderInlineStyle.MONOSPACE,
    "tt" to ReaderInlineStyle.MONOSPACE,
    "sup" to ReaderInlineStyle.SUPERSCRIPT,
    "sub" to ReaderInlineStyle.SUBSCRIPT,
)

/**
 * [decodeXmlEntities]가 직접 처리하는 숫자형 `&#…;`/`&#x…;` 외에 해석하는 이름 있는
 * XML/HTML 문자 참조들.
 */
private val NamedEntities: Map<String, String> = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
    "shy" to "­", "ndash" to "–", "mdash" to "—", "horbar" to "―",
    "lsquo" to "‘", "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“", "rdquo" to "”", "bdquo" to "„",
    "lsaquo" to "‹", "rsaquo" to "›", "laquo" to "«", "raquo" to "»",
    "hellip" to "…", "middot" to "·", "bull" to "•", "dagger" to "†", "Dagger" to "‡",
    "prime" to "′", "Prime" to "″", "permil" to "‰", "para" to "¶", "sect" to "§",
    "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°", "plusmn" to "±",
    "times" to "×", "divide" to "÷", "frac12" to "½", "frac14" to "¼", "frac34" to "¾",
    "sup1" to "¹", "sup2" to "²", "sup3" to "³", "micro" to "µ",
    "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢", "curren" to "¤",
    "larr" to "←", "uarr" to "↑", "rarr" to "→", "darr" to "↓", "harr" to "↔",
    "hearts" to "♥", "diams" to "♦", "clubs" to "♣", "spades" to "♠",
    "iexcl" to "¡", "iquest" to "¿", "ordf" to "ª", "ordm" to "º", "not" to "¬",
    "brvbar" to "¦", "uml" to "¨", "macr" to "¯", "acute" to "´", "cedil" to "¸",
)

/**
 * 렌더러의 모델이 담는 형태로 표현된 블록의 완전히 해석된 스타일 — 모든 길이가 이미 기준 em 단위이므로
 * 어떤 소비자도 원시 선언을 재해석하지 않는다.
 */
private fun ComputedStyle.toReaderBlockStyle(css: EpubCss): ReaderBlockStyle = ReaderBlockStyle(
    fontScale = fontScale.takeIf { it != 1f },
    bold = declarations.fontWeight?.toBoldOrNull(),
    italic = declarations.fontStyle?.toItalicFlag(),
    fontFamily = declarations.fontFamily?.toReaderFontFamily(),
    fontFamilyName = declarations.fontFamily?.toPublisherFontFamilyName(),
    fontHref = css.resolvedFontHref(declarations.fontFamily),
    lineHeightScale = lineHeightBaseEm(),
    textIndentEm = textIndentEm,
    marginTopEm = declarations.marginTop?.toResolvedMarginEm(fontScale),
    marginBottomEm = declarations.marginBottom?.toResolvedMarginEm(fontScale),
    marginStartEm = declarations.marginLeft?.toResolvedMarginEm(fontScale),
    marginEndEm = declarations.marginRight?.toResolvedMarginEm(fontScale),
    paddingTopEm = declarations.paddingTop?.toResolvedMarginEm(fontScale),
    paddingBottomEm = declarations.paddingBottom?.toResolvedMarginEm(fontScale),
    paddingStartEm = declarations.paddingLeft?.toResolvedMarginEm(fontScale),
    paddingEndEm = declarations.paddingRight?.toResolvedMarginEm(fontScale),
    insetStartEm = insetStartEm.takeIf { it > 0f },
    insetEndEm = insetEndEm.takeIf { it > 0f },
    underline = underline,
    lineThrough = lineThrough,
    foregroundColor = declarations.color?.toReaderColorOrNull(),
    boxStyle = declarations.toReaderBoxStyle(),
)

/**
 * 이 스타일에서 상속된 시각적 부분만 — 묵시적으로 열린 블록(래퍼 안에 직접 설정된 텍스트로,
 * 자체 블록 태그가 없는)이 주변에서 가져가는 것. 래퍼 자신의 박스(마진, 패딩, 테두리)는
 * 래퍼에 남는다; 여기로 가져오면 모든 묵시적 단락에 래퍼의 마진이 적용된다.
 */
private fun ComputedStyle.toInheritedReaderBlockStyle(css: EpubCss): ReaderBlockStyle? = ReaderBlockStyle(
    fontScale = fontScale.takeIf { it != 1f },
    bold = declarations.fontWeight?.toBoldOrNull(),
    italic = declarations.fontStyle?.toItalicFlag(),
    fontFamily = declarations.fontFamily?.toReaderFontFamily(),
    fontFamilyName = declarations.fontFamily?.toPublisherFontFamilyName(),
    fontHref = css.resolvedFontHref(declarations.fontFamily),
    lineHeightScale = lineHeightBaseEm(),
    textIndentEm = textIndentEm,
    insetStartEm = insetStartEm.takeIf { it > 0f },
    insetEndEm = insetEndEm.takeIf { it > 0f },
    underline = underline,
    lineThrough = lineThrough,
    foregroundColor = declarations.color?.toReaderColorOrNull(),
).takeIf { !it.isEmpty() }

/** 이 `font-style` 값이 이탤릭 서체를 요구하는지 여부. */
private fun String.toItalicFlag(): Boolean = this == "italic" || this == "oblique"

/**
 * 이 `text-decoration` 값이 [decoration]을 요구하는지, 또는 값이 장식에 대해 아무것도 말하지 않으면 null.
 *
 * `none`은 침묵이 아닌 답이다: 책이 링크에 `text-decoration: none`을 쓰면 밑줄이 없음을 말하는 것이며,
 * 이것을 "명시되지 않음"으로 읽으면 리더 자체의 밑줄이 켜진 상태로 남는다. 다른 장식(`overline` 등)을
 * 지정하는 값은 [decoration]을 끄지 않고 명시되지 않은 상태로 남긴다. 책이 다른 것에 대해 말하고 있기 때문이다.
 *
 * @receiver 선언 파서가 이미 소문자로 변환한 원시 선언 값.
 * @param decoration 물어보는 장식, 예: `"underline"`.
 * @return 값이 요구하면 true, 값이 `none`이면 false, 그 외는 null.
 */
private fun String.toDecorationFlag(decoration: String): Boolean? = when {
    contains(decoration) -> true
    trim() == "none" -> false
    else -> null
}

/**
 * 이 `font-weight` 값을 굵기 플래그로 반환하거나, 인식된 키워드나 숫자가 아니면 null.
 *
 * 숫자 굵기는 [BoldWeightThreshold](600) 이상에서 굵음으로 읽힌다.
 * CSS 굵기 척도에서 세미-볼드가 위치하는 곳이다.
 */
private fun String.toBoldOrNull(): Boolean? = when {
    this == "bold" || this == "bolder" -> true
    this == "normal" || this == "lighter" -> false
    toIntOrNull() != null -> toInt() >= BoldWeightThreshold
    else -> null
}

/**
 * 선언이 요청하는 일반 패밀리. 책이 자체 번들 서체를 이름으로 지정하면 대신 리더의 글꼴을 사용한다:
 * 해당 서체는 여기에 설치되어 있지 않으며, 대체를 추측하면 독자가 요청하지 않은 이유로 페이지가 달라진다.
 */
private fun String.toReaderFontFamily(): ReaderFontFamily? = when {
    contains("monospace") || contains("courier") -> ReaderFontFamily.MONOSPACE
    contains("sans-serif") -> ReaderFontFamily.SANS_SERIF
    contains("serif") -> ReaderFontFamily.SERIF
    else -> null
}

private fun String.toPublisherFontFamilyName(): String? =
    split(',').map(String::trim).map { it.removeSurrounding("\"").removeSurrounding("'") }
        .firstOrNull { family ->
            family.isNotEmpty() &&
                !family.equals("serif", ignoreCase = true) &&
                !family.equals("sans-serif", ignoreCase = true) &&
                !family.equals("monospace", ignoreCase = true) &&
                !family.equals("cursive", ignoreCase = true) &&
                !family.equals("fantasy", ignoreCase = true) &&
                !family.equals("system-ui", ignoreCase = true)
        }

private fun ReaderFloat.toTextAlign(): ReaderTextAlign = when (this) {
    ReaderFloat.START -> ReaderTextAlign.START
    ReaderFloat.END -> ReaderTextAlign.END
}

/**
 * 이 CSS `text-align` 키워드를 [ReaderTextAlign]으로 반환하거나, 인식된 값이 아니면 null.
 */
private fun String.toReaderTextAlign(): ReaderTextAlign? = when (this) {
    "center" -> ReaderTextAlign.CENTER
    "right", "end" -> ReaderTextAlign.END
    "justify" -> ReaderTextAlign.JUSTIFY
    "left", "start" -> ReaderTextAlign.START
    else -> null
}

private fun CssDeclarations.toReaderBoxStyle(): ReaderBoxStyle? = ReaderBoxStyle(
    backgroundColor = backgroundColor?.toReaderColorOrNull(),
    borderTop = borderTop.toReaderBorderOrNull(),
    borderRight = borderRight.toReaderBorderOrNull(),
    borderBottom = borderBottom.toReaderBorderOrNull(),
    borderLeft = borderLeft.toReaderBorderOrNull(),
    borderRadiusPercent = borderRadius.toBorderRadiusPercentOrNull(),
).takeUnless(ReaderBoxStyle::isEmpty)

private fun CssBorder?.toReaderBorderOrNull(): ReaderBorder? = this?.let {
    ReaderBorder(
        widthPx = it.width.toPxOrNull(),
        color = it.color?.toReaderColorOrNull(),
    ).takeIf { border -> border.widthPx != null || border.color != null }
}

private fun CssLength?.toPxOrNull(): Float? = when (this) {
    is CssLength.Em -> value * CssDefaultFontPx
    is CssLength.Percent -> null
    is CssLength.Px -> value
    null -> null
}

private fun CssLength?.toBorderRadiusPercentOrNull(): Float? = when (this) {
    is CssLength.Percent -> fraction * 100f
    else -> null
}

/**
 * 컨테이너가 페이지를 그릴 수 있는 무언가를 담고 있는지 여부: 칠할 배경이나 테두리, 또는
 * 자신의 콘텐츠를 그 가장자리에서 떼어놓는 간격.
 *
 * 간격이 중요한 이유는 `body { margin: 2em }`이 리플로어블 책이 페이지 마진을 표현하는 방식이기 때문이다.
 * 배경이 있을 때만 컨테이너를 기록하면 그것을 버리게 되고 — 그러면 텍스트가 책이 조판된 것보다
 * 훨씬 넓은 열에 가장자리에서 가장자리까지 배치된다.
 */
private fun ReaderBlockStyle.hasVisualContainerData(): Boolean =
    boxStyle?.isEmpty() == false || hasBoxSpacing()

/** 이 스타일이 어느 변에든 마진이나 패딩을 명시하는지 여부. */
private fun ReaderBlockStyle.hasBoxSpacing(): Boolean = listOf(
    marginTopEm, marginBottomEm, marginStartEm, marginEndEm,
    paddingTopEm, paddingBottomEm, paddingStartEm, paddingEndEm,
).any { side -> side != null && side > 0f }

private fun String.toReaderColorOrNull(): ReaderColor? {
    val value = trim().lowercase()
    return when {
        value == "transparent" -> ReaderColor(0x00000000)
        value == "black" -> ReaderColor(0xFF000000)
        value == "white" -> ReaderColor(0xFFFFFFFF)
        value == "red" -> ReaderColor(0xFFFF0000)
        value == "blue" -> ReaderColor(0xFF0000FF)
        value == "green" -> ReaderColor(0xFF008000)
        value == "gray" || value == "grey" -> ReaderColor(0xFF808080)
        value.startsWith("#") -> parseHexColor(value.removePrefix("#"))
        value.startsWith("rgb(") -> parseRgbColor(value, hasAlpha = false)
        value.startsWith("rgba(") -> parseRgbColor(value, hasAlpha = true)
        else -> null
    }
}

private fun parseHexColor(hex: String): ReaderColor? {
    val expanded = when (hex.length) {
        3 -> hex.flatMap { listOf(it, it) }.joinToString("")
        4 -> hex.flatMap { listOf(it, it) }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    val argb = when (expanded.length) {
        6 -> "FF$expanded"
        8 -> expanded.takeLast(2) + expanded.dropLast(2)
        else -> return null
    }
    return argb.toLongOrNull(16)?.let(::ReaderColor)
}

private fun parseRgbColor(value: String, hasAlpha: Boolean): ReaderColor? {
    val inner = value.substringAfter('(').substringBeforeLast(')')
    val parts = inner.split(',').map(String::trim)
    if (parts.size != if (hasAlpha) 4 else 3) return null
    val channels = parts.take(3).map { it.toFloatOrNull()?.coerceIn(0f, 255f)?.toInt() ?: return null }
    val alpha = if (!hasAlpha) 255 else {
        val raw = parts[3].toFloatOrNull() ?: return null
        (raw.coerceIn(0f, 1f) * 255f).toInt()
    }
    return ReaderColor(
        ((alpha.toLong() and 0xFF) shl 24) or
            ((channels[0].toLong() and 0xFF) shl 16) or
            ((channels[1].toLong() and 0xFF) shl 8) or
            (channels[2].toLong() and 0xFF),
    )
}

/**
 * `px` 길이를 상대 배율로 변환할 때 기준이 되는 픽셀 단위 기본 글꼴 크기.
 * 일반적인 브라우저 기본값과 일치한다.
 */
private const val CssDefaultFontPx = 16f

/** [String.toBoldOrNull]이 굵음으로 읽는 숫자 `font-weight`의 최솟값(이상). */
private const val BoldWeightThreshold = 600
