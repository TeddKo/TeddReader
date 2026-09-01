package com.tedd.teddreader.core.data.parser

/**
 * EPUB의 목차. EPUB3 nav 문서든 EPUB2 NCX든 원본 소스에서 읽어온 것으로, 책의 실제 스파인과 섹션
 * 오프셋에 맞춰 해석되기 전 단계다. 아주 다른 두 소스 포맷이 공통으로 갖는 것 — 선택적 제목과
 * 평평하고 들여쓰기 태그가 붙은 항목 목록 — 만 담아두어, 어느 목차 포맷으로 책이 만들어졌든
 * [parseEpubNavDocument]와 [parseNcxDocument]가 같은 형태를 반환하게 한다.
 *
 * @property heading 원본 마크업에 제목이 있었다면 그 목차 자체의 제목 텍스트(예: "Contents").
 * @property entries 문서 순서대로의 목차 항목들.
 */
internal data class ParsedNavigation(
    val heading: String? = null,
    val entries: List<ParsedNavigationEntry> = emptyList(),
)

/**
 * EPUB 목차의 항목 하나. `href`가 파싱된 책의 섹션 인덱스와 문자 오프셋으로 아직 해석되기 전 단계다.
 *
 * @property title 항목의 표시 텍스트.
 * @property level 중첩 깊이. 최상위 항목은 1부터 시작하며, EPUB3 `<ol>`이나 NCX `navPoint` 안에
 *   중첩된 항목일수록 더 깊어진다.
 * @property href 이 항목이 가리키는 링크 대상. 원본 마크업에 쓰인 그대로 — 스파인 문서 경로,
 *   선택적으로 `#fragment` 앵커가 붙는다.
 */
internal data class ParsedNavigationEntry(
    val title: String,
    val level: Int,
    val href: String,
)

/**
 * EPUB3 내비게이션 문서의 목차를 읽는다: `epub:type`(또는 `epub:type` 속성이 생기기 전 시절의 책에서
 * 보이는 `type`/`role`)에 `toc`가 포함된 `<nav>` 요소를, 랜드마크나 페이지 목록용으로 여러 개 있을 수
 * 있는 `<nav>` 요소들 중에서 찾는다.
 *
 * 본문은 DOM으로 파싱되지 않고 평평한 토큰 스트림으로 순회된다. [ParsedNavigationEntry.level]을 위해
 * `<ol>` 중첩 깊이를 추적하고, 각 `<a>` 안에서는 텍스트 콘텐츠를 제목으로 모으되 — 텍스트 대신 표지
 * 썸네일을 담은(일부 책이 유일한 챕터 라벨로 쓰는 방식) 링크를 위해 내부 `<img>`의 `alt`나 `title`
 * 속성으로 폴백한다. `href`가 없거나 제목이 빈 항목은 자리표시자를 넣는 대신 그냥 버려진다.
 *
 * @param xhtml nav 문서의 원시 마크업.
 * @return 찾은 제목과 항목들, 또는 `toc` 타입의 `<nav>`가 없거나 그 여는 태그에 대응하는 닫는 태그가
 *   없다면 빈 [ParsedNavigation](잘못된 마크업이라도 여기서 예외를 던지는 일은 없다 — 그저 보여줄
 *   것이 없다는 결과만 낼 뿐이다).
 */
internal fun parseEpubNavDocument(xhtml: String): ParsedNavigation {
    val navMatch = NavOpenTagRegex
        .findAll(xhtml)
        .firstOrNull { match ->
            navTypeTokens(parseAttributes(match.value)).contains("toc")
        } ?: return ParsedNavigation()
    val navEnd = findMatchingEndTag(xhtml, navMatch.range.first, NavTagPairRegex) ?: return ParsedNavigation()
    val navBody = xhtml.substring(navMatch.range.last + 1, navEnd.start)
    val heading = NavHeadingRegex
        .find(navBody)
        ?.groupValues
        ?.get(2)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)

    val entries = mutableListOf<ParsedNavigationEntry>()
    val tokens = MarkupTokenRegex.findAll(navBody)
    var listDepth = 0
    var captureDepth = 0
    var pendingHref: String? = null
    val label = StringBuilder()
    for (token in tokens) {
        val value = token.value
        if (!value.startsWith('<')) {
            if (captureDepth > 0) label.append(decodeXmlEntities(value))
            continue
        }
        val tag = parseNavToken(value)
        when {
            !tag.isClosing && tag.name == "ol" -> listDepth += 1
            tag.isClosing && tag.name == "ol" && listDepth > 0 -> listDepth -= 1
            !tag.isClosing && tag.name == "a" -> {
                pendingHref = tag.attributes["href"]?.let(::decodeXmlEntities)
                label.clear()
                captureDepth = if (pendingHref != null) 1 else 0
            }
            !tag.isClosing && captureDepth > 0 && tag.name == "img" -> {
                val altOrTitle = tag.attributes["alt"]?.takeIf(String::isNotBlank)
                    ?: tag.attributes["title"]?.takeIf(String::isNotBlank)
                if (altOrTitle != null) label.append(altOrTitle)
            }
            !tag.isClosing && captureDepth > 0 -> captureDepth += 1
            tag.isClosing && captureDepth > 0 && tag.name == "a" -> {
                val href = pendingHref
                val title = label.toString().replace(WhitespaceRunRegex, " ").trim()
                if (href != null && title.isNotEmpty()) {
                    entries += ParsedNavigationEntry(
                        title = title,
                        level = listDepth.coerceAtLeast(1),
                        href = href,
                    )
                }
                pendingHref = null
                label.clear()
                captureDepth = 0
            }
            tag.isClosing && captureDepth > 0 -> captureDepth = (captureDepth - 1).coerceAtLeast(0)
        }
    }
    return ParsedNavigation(heading = heading, entries = entries)
}

/**
 * EPUB2 NCX 문서의 목차를 읽는다 — 예전 도구로 만들어진 책들이 EPUB3 nav 문서와 함께, 또는 그 대신
 * 여전히 담고 있는 레거시 포맷. `docTitle`/`text`가 제목을 제공하고, 중첩된 `navPoint` 요소들
 * ([parseNcxNavPoints])이 항목들을 제공하며, 항목의 중첩 깊이가 그대로 그 항목의
 * [ParsedNavigationEntry.level]이 된다.
 *
 * @param xml NCX 문서의 원시 마크업.
 * @return 찾은 제목과 항목들; 문서에 `navPoint`가 없거나 매칭할 수 없을 만큼 손상되어 있다면
 *   entries는 비어 있다.
 */
internal fun parseNcxDocument(xml: String): ParsedNavigation {
    val heading = NcxDocTitleRegex
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)
    val entries = mutableListOf<ParsedNavigationEntry>()
    parseNcxNavPoints(xml, 1, entries)
    return ParsedNavigation(heading = heading, entries = entries)
}

/**
 * [xml] 안의 모든 `navPoint`에서 [ParsedNavigationEntry]를 문서 순서대로 [entries]에 재귀적으로
 * 모은다. 각 `navPoint`의 본문 안으로 내려가 [level] + 1에 중첩된 것들을 찾는다. `navLabel`/`text`
 * 제목이나 `content`의 `src`가 없는 `navPoint`는 건너뛴다 — 그 자식들은 여전히 방문하되, 빈 제목이나
 * href로 추가하지는 않는다.
 *
 * @param xml 이 레벨에서 `navPoint` 요소를 찾기 위해 스캔할 마크업; NCX 문서 전체이거나, 재귀
 *   호출일 때는 어느 `navPoint`의 본문이다.
 * @param level [xml]에서 직접 발견된 항목들에 부여할 중첩 깊이.
 * @param entries 항목이 추가되는 목록. 재귀 순회 전체에서 공유된다.
 */
private fun parseNcxNavPoints(xml: String, level: Int, entries: MutableList<ParsedNavigationEntry>) {
    var index = 0
    while (true) {
        val start = NcxNavPointOpenRegex.find(xml, index) ?: break
        val end = findMatchingEndTag(xml, start.range.first, NcxNavPointTagPairRegex) ?: break
        val body = xml.substring(start.range.last + 1, end.start)
        val title = NcxNavLabelTextRegex
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.let(::stripMarkup)
            ?.trim()
            .orEmpty()
        val href = NcxContentSrcRegex
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.let(::decodeXmlEntities)
            .orEmpty()
        if (title.isNotEmpty() && href.isNotEmpty()) {
            entries += ParsedNavigationEntry(title = title, level = level, href = href)
        }
        parseNcxNavPoints(body, level + 1, entries)
        index = end.end
    }
}

/** [findMatchingEndTag]가 찾은 닫는 태그의 범위. `<`부터 `>`까지 포함한다. */
private data class EndTagRange(val start: Int, val end: Int)

/**
 * [startIndex]에서 열린 요소에 대응하는 닫는 태그를 찾는다. 그 안에 중첩된 같은 이름의 다른 요소들
 * (예: `<ol>` 안의 `<ol>`)과, 애초에 중첩 깊이를 늘리지 않는 자체 닫힘 태그를 함께 고려한다.
 *
 * 요소 이름 대신 이미 컴파일된 태그 패턴을 받는 이유는, [parseNcxNavPoints]가 책의 전체 목차를
 * 순회하며 각 항목의 자식들로 재귀하는 동안 `navPoint`마다 이 함수를 한 번씩 호출하기 때문이다 —
 * 여기서 이름으로부터 패턴을 만들면 모든 중첩 레벨의 모든 항목마다 정규식 컴파일 비용이 청구되었다.
 *
 * @param text 스캔할 마크업.
 * @param startIndex 여는 태그 자신의 `<`의 위치(그 위치이거나 그 이전).
 * @param tagPair 요소의 여는 형태와 닫는 형태를 모두 매칭하는 패턴. 닫는 형태에서는 그룹 1에 `/`를
 *   캡처한다. 즉 [NavTagPairRegex]나 [NcxNavPointTagPairRegex].
 * @return 매칭된 닫는 태그의 범위, 또는 [text]에 그것에 대응하는 균형 잡힌 닫힘이 없다면 `null` —
 *   추측하지 않고 그 요소에 대해서는 포기하는, 이 파서가 잘못된 마크업으로 취급하는 경우다.
 */
private fun findMatchingEndTag(text: String, startIndex: Int, tagPair: Regex): EndTagRange? {
    var depth = 0
    generateSequence(tagPair.find(text, startIndex)) { previous -> tagPair.find(text, previous.range.last + 1) }.forEach { match ->
        val raw = match.value
        val selfClosing = raw.endsWith("/>")
        val isClosing = match.groupValues[1] == "/"
        if (!isClosing) {
            depth += 1
            if (selfClosing) depth -= 1
        } else {
            depth -= 1
            if (depth == 0) return EndTagRange(match.range.first, match.range.last + 1)
        }
    }
    return null
}

/**
 * [parseEpubNavDocument]의 토큰 스트림에서 이미 분리된 태그 문자열 하나(예: `<a href="ch1.xhtml">`나
 * `</ol>`)를 그 이름, 속성, 열림/닫힘 상태로 파싱한다.
 *
 * @param raw 감싸는 `<`/`>`와, 자체 닫힘 태그라면 그 `/`까지 포함한 원시 태그 텍스트.
 * @return 파싱된 [NavToken]; 닫는 태그는 속성이 없으므로 속성 맵이 항상 비어 있다.
 */
private fun parseNavToken(raw: String): NavToken {
    val body = raw.removePrefix("<").removeSuffix(">").removeSuffix("/")
    val isClosing = body.startsWith("/")
    val normalized = body.removePrefix("/")
    val name = normalized.takeWhile { !it.isWhitespace() }.lowercase()
    return NavToken(
        name = name,
        attributes = if (isClosing) emptyMap() else parseAttributes(normalized),
        isClosing = isClosing,
    )
}

/**
 * [parseEpubNavDocument]의 토큰 스트림에서 나온 태그 하나. 상태 머신을 구동할 수 있을 만큼만 분해된
 * 형태.
 *
 * @property name 소문자화된 태그의 요소 이름.
 * @property attributes 태그의 속성들; 닫는 태그에서는 항상 비어 있다.
 * @property isClosing 여는/자체 닫힘 태그가 아니라 `</name>` 닫는 태그인지 여부.
 */
private data class NavToken(
    val name: String,
    val attributes: Map<String, String>,
    val isClosing: Boolean,
)

/**
 * HTML/XML 조각에서 모든 태그를 제거하고, 남은 부분의 엔티티를 디코딩하고, 공백을 축약한다 —
 * 제목이나 라벨의 마크업을 화면에 표시되는 그대로의 평문으로 바꾼다.
 *
 * @param value 벗겨낼 마크업 조각.
 * @return 모든 연속 공백이 하나의 스페이스로 축약되고 앞뒤 공백이 제거된, 조각의 텍스트 콘텐츠.
 */
internal fun stripMarkup(value: String): String = buildString {
    MarkupTokenRegex.findAll(value).forEach { token ->
        if (!token.value.startsWith('<')) append(decodeXmlEntities(token.value))
    }
}.replace(WhitespaceRunRegex, " ").trim()

/**
 * `<nav>` 태그의 `epub:type` 속성이 담는, 소문자화되고 공백으로 분리된 토큰 집합 — 또는 그 속성이
 * 생기기 전에 작성된 책이라면 대신 `type`이나 `role` 속성이 담는 것. [parseEpubNavDocument]는
 * 여러 개 있을 수 있는 `<nav>` 중 올바른 것을 찾기 위해 여기서 `toc` 토큰을 확인한다.
 *
 * @param attributes 태그의 파싱된 속성들.
 * @return `epub:type`, `type`, `role` 중 존재하는 것들에서 찾은 토큰들; 어느 것도 없다면 비어 있다.
 */
private fun navTypeTokens(attributes: Map<String, String>): Set<String> =
    sequenceOf(attributes["epub:type"], attributes["type"], attributes["role"])
        .filterNotNull()
        .flatMap { value -> decodeXmlEntities(value).split(WhitespaceRunRegex).asSequence() }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toSet()

/**
 * `<nav>` 여는 태그를 매칭한다. [parseEpubNavDocument]는 이후 이 태그의 속성에서 `toc` 타입 토큰을
 * 확인한다.
 */
private val NavOpenTagRegex = Regex("""(?is)<nav\b[^>]*>""")

/** [findMatchingEndTag]가 중첩 깊이의 균형을 맞출 수 있도록 `<nav …>`와 `</nav>`를 모두 매칭한다. */
private val NavTagPairRegex = Regex("""(?is)<(/?)nav\b[^>]*>""")

/** nav 문서 본문 안의 첫 제목 요소를 매칭한다. 그룹 2가 그 마크업이다. */
private val NavHeadingRegex = Regex("""(?is)<(h[1-6]|p)\b[^>]*>(.*?)</\1>""")

/**
 * 마크업을 태그와 그 사이 텍스트의 평평한 스트림으로 분리한다. [parseEpubNavDocument]의 상태
 * 머신과 [stripMarkup] 모두 이 토큰화를 순회한다.
 */
private val MarkupTokenRegex = Regex("""(?s)<[^>]+>|[^<]+""")

/**
 * 연속된 공백 한 구간을 매칭한다. 라벨 내부 공백을 축약하고 `nav` type 속성을 토큰으로 분리하는 데
 * 쓰인다.
 *
 * [stripMarkup]이 목차 항목 하나, 제목 하나마다 실행되므로, 개요가 큰 책은 임포트할 때마다 이
 * 패턴을 수백 번 컴파일했었다. 그래서 이 패턴은 끌어올려져 있다.
 */
private val WhitespaceRunRegex = Regex("""\s+""")

/** NCX `docTitle`의 텍스트 콘텐츠를 그룹 1에 캡처한다. [parseNcxDocument]가 보고하는 제목이다. */
private val NcxDocTitleRegex = Regex("""(?is)<docTitle>.*?<text>(.*?)</text>.*?</docTitle>""")

/** `navPoint` 여는 태그를 매칭한다. [parseNcxNavPoints]가 순회하는 요소다. */
private val NcxNavPointOpenRegex = Regex("""(?is)<navPoint\b[^>]*>""")

/** [findMatchingEndTag]가 중첩 깊이의 균형을 맞출 수 있도록 `<navPoint …>`와 `</navPoint>`를 모두 매칭한다. */
private val NcxNavPointTagPairRegex = Regex("""(?is)<(/?)navPoint\b[^>]*>""")

/** `navPoint`의 `navLabel`/`text` 제목을 그룹 1에 캡처한다. */
private val NcxNavLabelTextRegex = Regex("""(?is)<navLabel>.*?<text>(.*?)</text>.*?</navLabel>""")

/** `navPoint`의 `content` `src` 링크 대상을 그룹 1에 캡처한다. */
private val NcxContentSrcRegex = Regex("""(?is)<content\b[^>]*src\s*=\s*["']([^"']+)["'][^>]*/?>""")
