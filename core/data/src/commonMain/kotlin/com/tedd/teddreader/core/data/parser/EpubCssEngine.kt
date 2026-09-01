package com.tedd.teddreader.core.data.parser

/**
 * 여기서 EPUB이 실제로 그릴 수 있는 CSS의 부분집합.
 *
 * 리더는 책 한 권을 하나의 스타일 문자열로 배치하므로, 선언은 span이나 문단이 그것을 담을 수 있을 때만
 * 의미가 있다. 대부분의 박스 모델 속성(`display`, `position`, 실제 float 레이아웃 등)은 여전히 이 페이지까지
 * 도달하지 못한다. 여기 있는 것은 파서가 리더 소유 모델 타입으로 보존할 수 있는 부분집합으로, 퍼블리셔 색상,
 * 단순 테두리, 숨김 서브트리 억제, 임베디드 font-face 참조를 포함한다. 아래 캐스케이드가 범용적이기 때문에,
 * 지원 속성을 하나 더 추가하는 것은 여전히 이 목록에 항목 하나, 그리고 그것을 읽는 곳에 한 줄만 추가하면 된다.
 */
internal data class CssDeclarations(
    /** `text-align`의 원시 값(`"center"`, `"right"` 등)으로, 나중에 리더 정렬 값으로 변환된다. */
    val textAlign: String? = null,
    /** `font-size`. 이미 주변 텍스트 자신의 크기를 기준으로 상대적이다. */
    val fontSize: CssLength? = null,
    /**
     * `font-weight`의 원시 값 — `"bold"` 같은 키워드이거나 텍스트로 된 숫자 굵기값 — 이며, 나중에
     * bold 플래그로 변환된다.
     */
    val fontWeight: String? = null,
    /** `font-style`의 원시 값(`"italic"`, `"oblique"`, `"normal"`); 그 외의 값은 이탤릭이 아닌 것으로 읽힌다. */
    val fontStyle: String? = null,
    /**
     * `font-family`의 원시 값. 가능하면 리더 자체의 제네릭 패밀리와 매칭되며, 책이 하나를 번들했을 때
     * 연결된 `@font-face` href를 조회하는 데도 사용된다.
     */
    val fontFamily: String? = null,
    /** `line-height`. 단위 없는 값은 요소 자신의 폰트 크기의 배수로 유지된다. [parseLineHeight] 참고. */
    val lineHeight: CssLineHeight? = null,
    /** `color`의 원시 값. 나중의 CSS 색상 해석을 위해 텍스트로 그대로 전달된다. */
    val color: String? = null,
    /** `background-color`의 원시 값. 마찬가지로 나중의 해석을 위해 그대로 전달된다. */
    val backgroundColor: String? = null,
    /** 첫 줄의 `text-indent`. */
    val textIndent: CssLength? = null,
    /** `margin-top`; 상속되지 않는다 — [inheritable] 참고. */
    val marginTop: CssLength? = null,
    /** `margin-bottom`; 또한 두 문단 사이의 간격을 결정하는 값이기도 하다. */
    val marginBottom: CssLength? = null,
    /**
     * `text-decoration`의 원시 값(`"none"`, `"underline"`, `"line-through"` 등). 책이 링크 밑줄을 끈다고
     * 여기서 명시하면, 그것을 무시하는 리더는 책이 없앤 밑줄을 다시 그리게 된다.
     */
    val textDecoration: String? = null,
    /** `padding-top`. 박스의 콘텐츠를 자신의 위쪽 가장자리로부터 띄운다. */
    val paddingTop: CssLength? = null,
    /** `padding-right`. [paddingLeft]의 인라인 끝 쪽 대응값. */
    val paddingRight: CssLength? = null,
    /** `padding-bottom`. [paddingTop]의 수직 대응값. */
    val paddingBottom: CssLength? = null,
    /** `padding-left`. 인용문을 주변 텍스트로부터 들여쓰게 만드는 값이다. */
    val paddingLeft: CssLength? = null,
    /** `margin-left`; 상속되지 않으며, 블록의 인라인 시작 쪽 여백으로 읽힌다. */
    val marginLeft: CssLength? = null,
    /** `margin-right`; [marginLeft]의 인라인 끝 쪽 대응값. */
    val marginRight: CssLength? = null,
    /** `float`의 원시 값(`"left"`, `"right"`, `"none"`); 이미지 스타일링에서는 퍼블리셔 힌트로 읽힌다. */
    val float: String? = null,
    /** `width`. 이 규칙이 매칭하는 조상을 가진 이미지에 적용된다. */
    val width: CssLength? = null,
    /** `display`의 원시 값; `display:none`이 실제로 XHTML 파서가 소비하는 값이다. */
    val display: String? = null,
    /** 위쪽 테두리 선언. 축약형에서 왔든 명시적인 방향별 속성에서 왔든 상관없다. */
    val borderTop: CssBorder? = null,
    /** 오른쪽 테두리 선언. 축약형에서 왔든 명시적인 방향별 속성에서 왔든 상관없다. */
    val borderRight: CssBorder? = null,
    /** 아래쪽 테두리 선언. 축약형에서 왔든 명시적인 방향별 속성에서 왔든 상관없다. */
    val borderBottom: CssBorder? = null,
    /** 왼쪽 테두리 선언. 축약형에서 왔든 명시적인 방향별 속성에서 왔든 상관없다. */
    val borderLeft: CssBorder? = null,
    /** `border-radius`; 퍼센트 형태만 리더 모델까지 온전히 보존된다. */
    val borderRadius: CssLength? = null,
) {
    /** [other]를 이 값 위에 층층이 쌓은 결과 — 나중 규칙이나 더 구체적인 규칙이 하듯이. */
    fun mergedWith(other: CssDeclarations): CssDeclarations = CssDeclarations(
        textAlign = other.textAlign ?: textAlign,
        fontSize = other.fontSize ?: fontSize,
        fontWeight = other.fontWeight ?: fontWeight,
        fontStyle = other.fontStyle ?: fontStyle,
        fontFamily = other.fontFamily ?: fontFamily,
        lineHeight = other.lineHeight ?: lineHeight,
        color = other.color ?: color,
        backgroundColor = other.backgroundColor ?: backgroundColor,
        textIndent = other.textIndent ?: textIndent,
        marginTop = other.marginTop ?: marginTop,
        marginBottom = other.marginBottom ?: marginBottom,
        marginLeft = other.marginLeft ?: marginLeft,
        marginRight = other.marginRight ?: marginRight,
        textDecoration = other.textDecoration ?: textDecoration,
        paddingTop = other.paddingTop ?: paddingTop,
        paddingRight = other.paddingRight ?: paddingRight,
        paddingBottom = other.paddingBottom ?: paddingBottom,
        paddingLeft = other.paddingLeft ?: paddingLeft,
        float = other.float ?: float,
        width = other.width ?: width,
        display = other.display ?: display,
        borderTop = other.borderTop ?: borderTop,
        borderRight = other.borderRight ?: borderRight,
        borderBottom = other.borderBottom ?: borderBottom,
        borderLeft = other.borderLeft ?: borderLeft,
        borderRadius = other.borderRadius ?: borderRadius,
    )

    /**
     * 자식이 시작점으로 삼는 값: CSS가 상속되는 것으로 정의한 원시 텍스트 속성들, 오직 그것들뿐이다.
     *
     * `font-size`, `line-height`, `text-indent`도 상속되긴 하지만 여기서는 아니다 — 이들은 스타일
     * 리졸버가 계산하는 *숫자*로서 상속된다(`em`은 조상을 거치며 누적되고, 단위 없는 `line-height`는
     * 각 요소 자신의 크기를 다시 곱한다). 원시 선언을 그대로 내려보내면 모든 자손이 잘못된 기준으로
     * 다시 해석하게 됐다. `text-decoration`은 전혀 상속되지 않는다: CSS는 조상의 장식을 자손 전체에
     * 걸쳐 그리는데, 이는 리졸버가 별도로 모델링한다 — 이를 상속시키면 각 자식의 밑줄을 그 자식 자신의
     * 두께로 다시 그리게 됐다.
     */
    fun inheritable(): CssDeclarations = CssDeclarations(
        textAlign = textAlign,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        fontFamily = fontFamily,
        color = color,
    )

    /** 여기 있는 원시 `inherit` 키워드를 이미 상속된 [parent] 값으로 되돌려 해석한 결과. */
    fun resolvedInheritedKeywords(parent: CssDeclarations): CssDeclarations = copy(
        textAlign = textAlign.resolveInheritedKeyword(parent.textAlign),
        fontWeight = fontWeight.resolveInheritedKeyword(parent.fontWeight),
        fontStyle = fontStyle.resolveInheritedKeyword(parent.fontStyle),
        fontFamily = fontFamily.resolveInheritedKeyword(parent.fontFamily),
        color = color.resolveInheritedKeyword(parent.color),
    )

    /**
     * 여기 있는 모든 속성이 설정되지 않았을 때 true — 즉 요소가 보이는 방식을 전혀 바꾸지 않는
     * 규칙(또는 규칙 없음)이다.
     */
    fun isEmpty(): Boolean = this == Empty

    /** 캐스케이드에서 아무것도 적용되지 않을 때 반환되는 공유 무동작 인스턴스 [Empty]를 보관한다. */
    companion object {
        /**
         * 무동작 선언 집합: 모든 속성이 설정되지 않았으며, 캐스케이드에서 아무것도 적용되지 않을 때
         * 반환된다.
         */
        val Empty = CssDeclarations()
    }
}

private fun String?.resolveInheritedKeyword(parent: String?): String? =
    if (this?.equals("inherit", ignoreCase = true) == true) parent else this

/** CSS 너비. EPUB 스타일시트가 실제로 그림 크기를 지정할 때 쓰는 단위들로 표현된다. */
internal sealed interface CssWidth {
    /** `width: 75%`. 포함 블록에 대한 비율로 표현된다. */
    data class Percent(val fraction: Float) : CssWidth

    /** `width: 6.5em`. */
    data class Em(val value: Float) : CssWidth
}

/**
 * 이 엔진이 해석할 수 있는 형태의 CSS 길이 — 컨테이너 기준, 현재 폰트 크기 기준, 또는 절대값.
 */
internal sealed interface CssLength {
    /** `n%`. 크기가 정해지는 속성이 정의한 기준에 대한 비율이다. */
    data class Percent(val fraction: Float) : CssLength
    /** `n em`/`n rem`, 또는 이미 단위 없는 `line-height`를 같은 배수로 정규화한 값. */
    data class Em(val value: Float) : CssLength
    /** `n px` 또는 `n pt`. 필요한 곳에서는 리더 자체의 기본 폰트 크기를 기준으로 읽힌다. */
    data class Px(val value: Float) : CssLength
}

/**
 * CSS 상속이 의존하는 구분을 그대로 유지한 `line-height` 값.
 *
 * 단위 없는 `line-height: 1.6`은 그것이 최종적으로 적용되는 요소에 대한 *배수*다 — `body`로부터
 * 이를 상속받는 제목 요소는 `1.6 × 자신의 활자 크기`를 얻지, `1.6 × 본문 텍스트 크기`를 얻지 않는다.
 * 길이(`1.6em`, `24px`)는 선언한 요소에 대해 한 번 계산되고, 그 고정된 크기로 상속된다. 이 둘을
 * 하나의 길이로 뭉뚱그리면 모든 큰 글자 블록의 줄 간격이 너무 좁아졌다.
 */
internal sealed interface CssLineHeight {
    /** 단위 없는 배수. 상속받는 각 요소 자신의 폰트 크기로 다시 곱해진다. */
    data class Factor(val value: Float) : CssLineHeight
    /** 명시적 길이. 선언한 요소에서 한 번 계산된다. */
    data class Length(val length: CssLength) : CssLineHeight
}

/** 파서가 여전히 보존할 수 있는 테두리 한 변: 너비와 색상, 스타일은 "존재함"으로만 축약된다. */
internal data class CssBorder(
    val width: CssLength? = null,
    val color: String? = null,
)

/** 파싱된 `@font-face` 하나: 정의된 패밀리 이름과, 있다면 해석된 임베디드 폰트 href. */
internal data class CssFontFace(
    val familyName: String,
    val srcHref: String?,
)

/** 연결된 스타일시트 하나와, 상대 `url(...)`을 위해 그것이 로드된 컨테이너 경로. */
internal data class CssStyleSheetSource(
    val path: String? = null,
    val css: String,
)

/** 매처가 보는 요소 하나: 태그, 클래스, id. */
internal data class CssElement(
    /** HTML 태그 이름, 예: `"p"` 또는 `"h1"`. */
    val tag: String,
    /**
     * 요소가 갖고 있는 모든 클래스; 선택자는 자신이 요구하는 클래스들이 이 집합의 부분집합일 때
     * 매칭된다.
     */
    val classes: Set<String> = emptySet(),
    /** 요소의 `id` 속성. 없으면 null. */
    val id: String? = null,
)

/** 복합 선택자 하나 — `h1`, `.note`, `h1.note#id` — 매칭해야 할 조각들로 표현된다. */
private data class CompoundSelector(
    /** 필수 태그 이름. 복합 선택자에 태그 부분이 없으면(예: `.note` 단독) null. */
    val tag: String?,
    /** 이 복합 선택자가 요구하는 모든 클래스; 매칭되는 요소는 이것들 전부를 가져야 한다. */
    val classes: Set<String>,
    /** 필수 id. 복합 선택자에 `#id` 부분이 없으면 null. */
    val id: String?,
) {
    /**
     * [element]가 이 복합 선택자를 만족하는지 여부: 있다면 태그, 있다면 id, 그리고 모든 클래스.
     */
    fun matches(element: CssElement): Boolean {
        if (tag != null && !tag.equals(element.tag, ignoreCase = true)) return false
        if (id != null && id != element.id) return false
        return element.classes.containsAll(classes)
    }

    /** CSS 명시도. 스펙이 정의한 대로 id > class > tag 순으로 정렬된다. */
    val specificity: Int get() = (if (id != null) 10_000 else 0) + classes.size * 100 + (if (tag != null) 1 else 0)
}

/** 선택자를 복합 부분들로 표현한 것. 가장 안쪽이 마지막; 자손 결합자만 인정된다. */
private data class CssSelector(
    /** 선택자의 복합 부분들. 가장 바깥 조상이 먼저, 선택된 요소 자신이 마지막. */
    val compounds: List<CompoundSelector>,
) {
    /** 모든 복합 부분 자체 명시도의 합 — CSS가 한 선택자 전체를 다른 것과 비교해 순위를 매기는 방식. */
    val specificity: Int get() = compounds.sumOf(CompoundSelector::specificity)

    /**
     * 선택자의 말단 복합 부분이 요구하는 소문자 태그. 그 복합 부분에 태그 부분이 없으면(`.note`,
     * `#lead`) null.
     *
     * 이것이 [EpubCss]가 규칙을 인덱싱하는 키다: 자신의 태그가 이 값과 같은 요소만 이 선택자를
     * 만족시킬 수 있으므로, 말단 태그가 있는 규칙은 그 태그 하나의 버킷에만 저장되고 다른 태그에는
     * 절대 검토되지 않는다. 태그가 없는 말단 복합 부분은 어떤 요소의 태그와도 매칭될 수 있으므로
     * 버킷으로 나눌 수 없다 — 이런 규칙들은 [EpubCss]가 항상 참조하는 폴백 목록으로 간다.
     */
    val terminalTag: String? get() = compounds.lastOrNull()?.tag?.lowercase()

    /**
     * [ancestors](가장 바깥이 먼저, 요소 자신이 마지막)가 이 선택자를 만족하는지 여부.
     *
     * `>`, `+`, `~`는 그냥 자손 결합자로 읽힌다: 더 엄격하게 처리하면 책이 의도한 스타일이
     * 적용되지 않게 되고, 자손을 자식으로 처리해도 같은 요소에 대한 두 규칙 중 어느 쪽이 이기는지는
     * 절대 바뀌지 않는다.
     */
    fun matches(ancestors: List<CssElement>): Boolean {
        if (compounds.isEmpty() || ancestors.isEmpty()) return false
        if (!compounds.last().matches(ancestors.last())) return false
        var compoundIndex = compounds.size - 2
        var ancestorIndex = ancestors.size - 2
        while (compoundIndex >= 0) {
            if (ancestorIndex < 0) return false
            if (compounds[compoundIndex].matches(ancestors[ancestorIndex])) compoundIndex -= 1
            ancestorIndex -= 1
        }
        return true
    }
}

/** 파싱된 `selector { declarations }` 규칙 하나. 명시도 동점 처리를 위해 시트 내 위치도 함께 가진다. */
private data class CssRule(
    /** [declarations]가 적용되려면 요소가 매칭해야 하는 것. */
    val selector: CssSelector,
    /** 규칙이 선언한 내용. 이미 [CssDeclarations]가 표현할 수 있는 속성으로 좁혀져 있다. */
    val declarations: CssDeclarations,
    /**
     * [EpubCss.parse]의 목록에 있는 모든 시트를 통틀어, 규칙이 파싱된 순서상의 인덱스 — 명시도가
     * 같으면 더 나중 규칙이 이긴다.
     */
    val order: Int,
)

/**
 * 규칙 하나와 시트 전체 캐스케이드 순서에서의 순위를 짝지은 것. 두 인덱스 버킷을 재정렬 없이 그
 * 순서로 다시 병합할 수 있게 해준다.
 *
 * [EpubCss]는 모든 규칙을 `(specificity, order)` 기준으로 한 번 정렬하고, 각 규칙에 그 위치를
 * 부여한다. 태그 버킷과 태그 없는 폴백 목록은 이미 각각 오름차순 [rank]로 정렬되어 있으므로, 한
 * 요소에 대한 두 후보 목록을 병합하는 것은 더 낮은 [rank]를 먼저 유지하는 선형 패스로 끝난다.
 * 이것이 바로 [EpubCss.declarationsFor]가 약속하는, 가장 약한 것부터 시작하는 캐스케이드 순서다 —
 * 쿼리마다 정렬할 필요도 없고, 전체 규칙 O(n) 스캔도 없다.
 *
 * @property rule 이 순위가 속한 규칙.
 * @property rank 시트 전체 `(specificity, order)` 정렬에서 규칙의 0부터 시작하는 위치; 낮은 순위일수록
 *   더 약하며 더 높은 순위보다 먼저 병합된다.
 */
private data class RankedRule(
    val rule: CssRule,
    val rank: Int,
)

/**
 * 책의 스타일시트를, 선택된 요소의 말단 태그로 인덱싱하여 XHTML 파서가 그 요소와 매칭될 수 있는
 * 규칙만 평가하도록 하면서도 원래 CSS 캐스케이드 순서를 그대로 보존한다.
 *
 * @property rulesByTag 말단 선택자가 태그를 지정하는, 캐스케이드 순위가 매겨진 규칙들을 그 소문자
 *   태그로 그룹화한 것.
 * @property taglessRules 말단 선택자가 어떤 태그와도 매칭될 수 있는, 캐스케이드 순위가 매겨진
 *   클래스/id 규칙들.
 * @property fontFaces 정규화된 퍼블리셔 패밀리 이름을 키로 하는 임베디드 폰트 파일들.
 */
internal class EpubCss private constructor(
    private val rulesByTag: Map<String, List<RankedRule>>,
    private val taglessRules: List<RankedRule>,
    private val fontFaces: Map<String, String>,
) {
    /**
     * [ancestors]의 마지막 요소에 적용되는 선언들. 가장 약한 것부터 시작하므로 호출자가 이들을 층층이
     * 쌓을 수 있다. 명시도가 같으면 선언 순서로 판가름하는데, 이는 브라우저가 하는 방식과 같다.
     *
     * 오직 두 후보 목록만 참조된다: 이 요소 자신의 태그를 이름으로 하는 말단 복합 부분을 가진 규칙들의
     * 버킷, 그리고 어떤 태그와도 매칭될 수 있는 태그 없는 폴백 규칙들. 다른 요소를 위한 태그 규칙은
     * [CssSelector.matches]에 아예 제공되지도 않는다 — 그것이 이 인덱스의 존재 이유이며, 이제 태그가
     * 많은 시트에서 요소당 비용이 전체 규칙 수 대신 그것을 기준으로 커지는 이유다. 두 목록 모두 이미
     * 오름차순 캐스케이드 순위로 도착하므로, [mergeByRank]는 재정렬 없이 하나의 가장 약한 것부터
     * 시작하는 스트림으로 그것들을 인터리브한다. 그리고 그 스트림에서 살아남은 것만 매칭 테스트되고
     * 접힌다.
     *
     * @param ancestors 스타일을 적용할 요소와 그 조상 체인. 가장 바깥이 먼저, 요소 자신이 마지막;
     *   빈 목록은 [CssDeclarations.Empty]로 해석된다.
     * @return 적용되는 병합된 선언들, 가장 약한 것부터. 아무것도 매칭되지 않으면 [CssDeclarations.Empty].
     */
    fun declarationsFor(ancestors: List<CssElement>): CssDeclarations {
        val element = ancestors.lastOrNull() ?: return CssDeclarations.Empty
        val tagBucket = rulesByTag[element.tag.lowercase()].orEmpty()
        if (tagBucket.isEmpty() && taglessRules.isEmpty()) return CssDeclarations.Empty
        var result = CssDeclarations.Empty
        mergeByRank(tagBucket, taglessRules) { ranked ->
            if (ranked.rule.selector.matches(ancestors)) {
                result = result.mergedWith(ranked.rule.declarations)
            }
        }
        return result
    }

    /** [fontFamily]에 나열된 이름 중 이 시트가 실제로 정의한 첫 패밀리의 임베디드 폰트 href. */
    fun resolvedFontHref(fontFamily: String?): String? {
        if (fontFamily.isNullOrBlank()) return null
        return splitFontFamilies(fontFamily).firstNotNullOfOrNull { family ->
            fontFaces[family.normalizeFontFamilyKey()]
        }
    }

    /** 이 책이 사용 가능한 규칙이나 `@font-face`를 전혀 선언하지 않았을 때 true. */
    fun isEmpty(): Boolean = rulesByTag.isEmpty() && taglessRules.isEmpty() && fontFaces.isEmpty()

    companion object {
        /** 무동작 스타일시트: 규칙도 없고 font-face도 없다. */
        val Empty = EpubCss(emptyMap(), emptyList(), emptyMap())

        /**
         * base 경로 없이 원시 스타일시트 텍스트를 파싱한다. 그래서 상대 `url(...)`은 해석될 수 없다.
         *
         * 목록 순서는 여전히 링크된 순서이므로 나중 시트가 동점을 이긴다.
         */
        fun parse(sheets: List<String>): EpubCss = parseSources(sheets.map { CssStyleSheetSource(css = it) })

        /**
         * [sheets]를 링크된 순서로 파싱하여 나중 시트가 동점을 이기고 자기 자신의 상대 base 경로를
         * 유지하도록 한다.
         *
         * 모든 시트에 걸친 모든 규칙이 여기서 `(specificity, order)` 기준으로 한 번 정렬된다 —
         * [EpubCss.declarationsFor]가 접어 넣어야 하는 바로 그 순서다 — 그리고 각각에 그 순서 내
         * 순위가 부여된다. 규칙들은 그런 다음 그 순위 순서를 보존한 채 태그별 인덱스와 태그 없는
         * 폴백 목록으로 나뉘므로, 요소별 쿼리는 절대 정렬하지 않고 매칭될 수 없는 태그를 절대 스캔하지
         * 않는다. 파싱 시점에 한 번 정렬하는 것이 이전의 전체 규칙 목록에 대한 쿼리별 필터-정렬을
         * 대체한다.
         *
         * @param sheets 링크된 스타일시트들, 링크 순서대로; 동점이면 나중 시트의 규칙이 이긴다.
         * @return 만들어진 [EpubCss]. 어떤 시트도 사용 가능한 규칙이나 `@font-face`를 선언하지 않았으면
         *   [Empty].
         */
        fun parseSources(sheets: List<CssStyleSheetSource>): EpubCss {
            val rules = mutableListOf<CssRule>()
            val fontFaces = linkedMapOf<String, String>()
            var order = 0
            sheets.forEach { source ->
                scanCssRules(
                    css = stripCssComments(source.css),
                    onFontFace = { body ->
                        parseFontFace(body, source.path)?.let { fontFace ->
                            fontFace.srcHref?.let { fontFaces[fontFace.familyName.normalizeFontFamilyKey()] = it }
                        }
                    },
                    onRule = { selectorText, body ->
                        val declarations = parseCssDeclarations(body)
                        if (declarations.isEmpty()) return@scanCssRules
                        selectorText.split(',').forEach { rawSelector ->
                            val selector = parseSelector(rawSelector) ?: return@forEach
                            rules += CssRule(selector, declarations, order)
                            order += 1
                        }
                    },
                )
            }
            if (rules.isEmpty() && fontFaces.isEmpty()) return Empty
            val ranked = rules
                .sortedWith(compareBy({ it.selector.specificity }, { it.order }))
                .mapIndexed { rank, rule -> RankedRule(rule, rank) }
            val rulesByTag = linkedMapOf<String, MutableList<RankedRule>>()
            val taglessRules = mutableListOf<RankedRule>()
            ranked.forEach { rankedRule ->
                val tag = rankedRule.rule.selector.terminalTag
                if (tag == null) {
                    taglessRules += rankedRule
                } else {
                    rulesByTag.getOrPut(tag) { mutableListOf() } += rankedRule
                }
            }
            return EpubCss(rulesByTag, taglessRules, fontFaces)
        }
    }
}

/**
 * 이미 순위대로 정렬된 두 후보 목록 [first]와 [second]를 하나의 오름차순 순위 스트림으로 걸으며,
 * 캐스케이드 순서대로 각 규칙에 대해 정확히 한 번씩 [onRule]을 호출한다.
 *
 * 두 목록 모두 [EpubCss.parseSources]에서 시트 전체 `(specificity, order)` 순위로 정렬되어 나오고,
 * 둘은 구성상 서로소다(규칙은 태그 버킷에 있거나 태그 없는 폴백에 있거나 둘 중 하나이지 결코 둘 다는
 * 아니다). 그래서 [RankedRule.rank]에 대한 표준 투 포인터 병합이 중복도 없고 재정렬도 없이 단일한
 * 가장 약한 것부터 시작하는 캐스케이드 순서를 재현한다. 둘 중 하나가 비어 있으면 나머지 하나만
 * 그대로 순회한다.
 *
 * @param first 오름차순 순위의 후보 목록 하나, 예: 요소의 태그 버킷.
 * @param second 오름차순 순위의 다른 후보 목록, 예: 태그 없는 폴백 규칙들.
 * @param onRule 두 목록에 걸쳐 오름차순 [RankedRule.rank]대로 규칙마다 한 번씩 호출된다.
 */
private inline fun mergeByRank(
    first: List<RankedRule>,
    second: List<RankedRule>,
    onRule: (RankedRule) -> Unit,
) {
    var firstIndex = 0
    var secondIndex = 0
    while (firstIndex < first.size && secondIndex < second.size) {
        if (first[firstIndex].rank <= second[secondIndex].rank) {
            onRule(first[firstIndex])
            firstIndex += 1
        } else {
            onRule(second[secondIndex])
            secondIndex += 1
        }
    }
    while (firstIndex < first.size) {
        onRule(first[firstIndex])
        firstIndex += 1
    }
    while (secondIndex < second.size) {
        onRule(second[secondIndex])
        secondIndex += 1
    }
}

/**
 * 하나의 스타일시트의 규칙들을 실제 중괄호 매칭으로 걷는다. 그래서 at-rule의 본문은 평면 정규식이
 * 규칙을 뜯어내는 텍스트가 아니라 진짜 *블록*이 된다.
 *
 * 이것이 조건부 스타일링을 조건부로 유지하는 경계선이다. 이전의 정규식 추출은 중첩 개념이 전혀
 * 없었기 때문에, `@media print { p { display:none } }`가 안쪽의 `p { … }`를 일반 규칙으로 매칭시켜
 * 화면에서 그 문단들을 숨겼다 — 이 리더에는 해당하지 않는 매체를 위해 책이 명시한 스타일링이었다.
 * 여기서는 모든 `{`가 자신에 매칭되는 `}`를 찾고(따옴표를 인식하므로 문자열 안의 중괄호는 결코
 * 깊이를 잘못 세지 않는다), 블록에 어떤 일이 일어나는지는 그 프렐류드에 달려 있다:
 *
 * - 일반 선택자: 자신의 본문과 함께 [onRule]에 전달된다;
 * - `@media`: [mediaQueryApplies]가 그 쿼리가 이 매체를 지칭한다고 판단할 때만 내려가며, 그 본문은
 *   재귀적으로 스캔되므로 적용되는 쿼리 안에 중첩된 규칙과 `@font-face`도 여전히 포함된다;
 * - `@font-face`: [onFontFace]에 전달된다;
 * - 그 외의 at-rule 블록(`@supports`, `@keyframes`, `@page`, 벤더 규칙): 본문까지 통째로
 *   건너뛴다 — 선택자 매처가 의사 클래스에 적용하는 것과 같은 "판단할 수 없으면 → 버린다" 정책이다;
 * - 문(statement) at-rule(`@import`, `@charset`, `@namespace`): 자신의 `;`까지 건너뛴다.
 *
 * 잘못된 형식의 입력은 소프트하게 실패한다: 닫히지 않은 블록은 시트의 나머지 전체를 자신의 본문으로
 * 소비하고, 낯선 `}`는 무시된다. 그래서 규칙 하나가 깨져도 그 뒤의 모든 규칙을 밀리게 하지 않는다.
 *
 * @param css 스타일시트 텍스트, 주석은 이미 제거된 상태.
 * @param onFontFace 적용되는 컨텍스트에서 발견된 각 `@font-face` 본문과 함께 호출된다.
 * @param onRule 각 일반 규칙의 선택자 목록 텍스트와 선언 본문과 함께 호출된다.
 */
private fun scanCssRules(
    css: String,
    onFontFace: (body: String) -> Unit,
    onRule: (selectorText: String, body: String) -> Unit,
) {
    var index = 0
    var preludeStart = 0
    while (index < css.length) {
        when (css[index]) {
            '"', '\'' -> index = css.skipQuoted(index)
            ';' -> {
                // 문 at-rule(`@import …;`)을 끝내거나, 규칙 사이의 낯선 찌꺼기를 끝낸다.
                preludeStart = index + 1
                index += 1
            }
            '}' -> {
                // 자기 자신의 열린 블록이 없는 낯선 닫는 괄호; 이것과 그 앞에 있던 것을 버린다.
                preludeStart = index + 1
                index += 1
            }
            '{' -> {
                val prelude = css.substring(preludeStart, index).trim()
                val bodyStart = index + 1
                val bodyEnd = css.matchingBraceEnd(index)
                val body = css.substring(bodyStart, bodyEnd)
                when {
                    prelude.startsWith("@media", ignoreCase = true) -> {
                        if (mediaQueryApplies(prelude.drop("@media".length))) {
                            scanCssRules(body, onFontFace, onRule)
                        }
                    }
                    prelude.startsWith("@font-face", ignoreCase = true) -> onFontFace(body)
                    prelude.startsWith("@") -> Unit
                    prelude.isNotEmpty() -> onRule(prelude, body)
                }
                index = if (bodyEnd < css.length) bodyEnd + 1 else css.length
                preludeStart = index
            }
            else -> index += 1
        }
    }
}

/**
 * [openIndex]에서 열린 블록을 닫는 `}`의 인덱스, 또는 시트가 블록이 열린 채로 끝나면 [String.length] —
 * 그러면 닫히지 않은 블록이 루프를 도는 대신 시트의 나머지를 통째로 삼킨다. 인용된 문자열은
 * 건너뛰므로 그 안의 중괄호는 결코 깊이를 바꾸지 않는다.
 */
private fun String.matchingBraceEnd(openIndex: Int): Int {
    var depth = 1
    var index = openIndex + 1
    while (index < length) {
        when (this[index]) {
            '"', '\'' -> {
                index = skipQuoted(index)
                continue
            }
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth == 0) return index
            }
        }
        index += 1
    }
    return length
}

/** [quoteIndex]에서 시작하는 인용된 문자열 바로 다음 인덱스; 종료되지 않은 것은 끝까지 이어진다. */
private fun String.skipQuoted(quoteIndex: Int): Int {
    val quote = this[quoteIndex]
    var index = quoteIndex + 1
    while (index < length) {
        when (this[index]) {
            '\\' -> index += 1
            quote -> return index + 1
        }
        index += 1
    }
    return length
}

/**
 * `@media` 쿼리가 이 리더가 해당하는 매체를 지칭하는지 여부: `all`, `screen`, 또는 아무것도 없음(CSS는
 * 이를 `all`로 읽는다). 쉼표로 구분된 목록의 어느 한 브랜치라도 그렇다면 — 선택적으로 `only`가 붙어도 —
 * 블록 전체가 적용된다.
 *
 * 특성 조건(`(min-width: 60em)`, `(orientation: …)`)을 가진 브랜치는 짐작하지 않고 *건너뛴다*: 이
 * 엔진은 스타일을 파싱 시점에 한 번 해석하고 특성을 평가할 뷰포트가 없으므로, 넓은 화면용 오버라이드를
 * 모든 폰에 적용하는 것은 정확히 이 스캔이 막으려는 종류의 스타일링 누수다. `print`/`speech`/기타
 * 매체 브랜치는 결코 적용되지 않는다. 이는 [parseCompound]가 의사 클래스에 적용하는 것과 같은
 * "판단할 수 없으면 → 버린다" 정책이다.
 *
 * @param query `@media`와 블록의 `{` 사이의 원시 텍스트.
 */
private fun mediaQueryApplies(query: String): Boolean = query.split(',').any { branch ->
    val cleaned = branch.trim().lowercase().removePrefix("only").trim()
    cleaned.isEmpty() || cleaned == "all" || cleaned == "screen"
}

/**
 * [raw](규칙의 선택자 목록 중 쉼표로 구분된 한 브랜치)를 [CssSelector]로 파싱한다. 이 매처가
 * 안전하게 판단할 수 없는 것 — at-rule 본문, 의사 요소, 속성 선택자 — 이면 null을 반환한다. 이 중
 * 하나라도 짐작하면 책의 print 전용 또는 상태 전용 스타일링을 모든 페이지에 적용할 위험이 있으므로,
 * 그 선택자 전체 — 그리고 그것이 속한 규칙 — 를 대신 버린다.
 *
 * @param raw 선택자 하나, 예: `"h1.title"` 또는 `".quote p"`.
 * @return 파싱된 선택자, 또는 [raw]가 비어 있거나 매칭하기에 안전하지 않다고 판단되면 null.
 */
private fun parseSelector(raw: String): CssSelector? {
    val cleaned = raw.trim()
    if (cleaned.isEmpty() || '@' in cleaned || '[' in cleaned || "::" in cleaned) return null
    val compounds = cleaned.split(CssCombinatorRegex).filter(String::isNotEmpty).map(::parseCompound)
    if (compounds.isEmpty() || compounds.any { it == null }) return null
    return CssSelector(compounds.filterNotNull())
}

/**
 * 복합 선택자 하나(`h1`, `.note`, `h1.note#id`)를 [CompoundSelector]로 파싱한다. 판단할 수 없으면
 * null을 반환한다: 의사 클래스는 이 매처가 관찰할 수 없는 상태로 규칙을 좁히고(`a:hover`는 `a`와
 * 같은 것이 아니다), 전체 선택자(`*`) 역시 이 파서가 해석하는 것이 아니다. 복합 선택자를 버리는 것 —
 * 그러면 선택자 전체가 버려진다 — 은 hover 전용과 print 전용 스타일링을, 호버되지도 인쇄되지도 않는
 * 페이지에서 배제해준다.
 *
 * @param raw 결합자가 없는, 선택자 안의 복합 부분 하나.
 * @return 파싱된 복합 부분, 또는 [raw]가 비어 있거나 의사 클래스나 `*`를 가지면 null.
 */
private fun parseCompound(rawCompound: String): CompoundSelector? {
    val raw = StatelessPseudoClassRegex.replace(rawCompound, "")
    if (raw.isEmpty() || ':' in raw || '*' in raw) return null
    val tag = raw.takeWhile { it != '.' && it != '#' }.takeIf(String::isNotEmpty)
    val classes = CssClassNameRegex.findAll(raw).map { it.groupValues[1] }.toSet()
    val id = CssIdRegex.find(raw)?.groupValues?.get(1)
    if (tag == null && classes.isEmpty() && id == null) return null
    return CompoundSelector(tag = tag, classes = classes, id = id)
}

/**
 * 규칙의 선언 블록을 [CssDeclarations]가 표현할 수 있는 속성의 부분집합으로 파싱하며, 그 외의
 * 것은 조용히 버린다 — [CssDeclarations] 자신의 클래스 문서가 설명하는 것과 같은 정책을, 블록을
 * 걷는 동안 속성별로 적용한다.
 *
 * @param body 규칙의 중괄호 사이 원시 텍스트, 예: `"text-align:center;float:left"`.
 * @return [body]에서 발견된 인식 가능한 선언들; 그릴 수 없는 속성이나 형식이 잘못된 `name:value`
 *   쌍은 규칙 전체를 실패시키는 대신 아무것도 기여하지 않는다.
 */
internal fun parseCssDeclarations(body: String): CssDeclarations {
    var result = CssDeclarations.Empty
    body.split(';').forEach { declaration ->
        val name = declaration.substringBefore(':', "").trim().lowercase()
        val value = declaration.substringAfter(':', "").trim().stripImportant()
        if (name.isEmpty() || value.isEmpty()) return@forEach
        result = when (name) {
            "text-align" -> result.copy(textAlign = value.lowercase())
            "text-decoration", "text-decoration-line" -> result.copy(textDecoration = value.lowercase())
            "padding-top" -> result.copy(paddingTop = parseLength(value))
            "padding-right" -> result.copy(paddingRight = parseLength(value))
            "padding-bottom" -> result.copy(paddingBottom = parseLength(value))
            "padding-left" -> result.copy(paddingLeft = parseLength(value))
            "padding" -> parseMarginShorthand(value)?.let { sides ->
                result.copy(
                    paddingTop = sides.top,
                    paddingRight = sides.right,
                    paddingBottom = sides.bottom,
                    paddingLeft = sides.left,
                )
            } ?: result
            "font-size" -> result.copy(fontSize = parseLength(value))
            "font-weight" -> result.copy(fontWeight = value.lowercase())
            "font-style" -> result.copy(fontStyle = value.lowercase())
            "font-family" -> result.copy(fontFamily = value)
            "line-height" -> result.copy(lineHeight = parseLineHeight(value))
            "color" -> result.copy(color = value)
            "background-color" -> result.copy(backgroundColor = value)
            "text-indent" -> result.copy(textIndent = parseLength(value))
            "float" -> result.copy(float = value.lowercase())
            "width" -> result.copy(width = parseLength(value))
            "display" -> result.copy(display = value.lowercase())
            "margin-top" -> result.copy(marginTop = parseLength(value))
            "margin-bottom" -> result.copy(marginBottom = parseLength(value))
            "margin-left" -> result.copy(marginLeft = parseLength(value))
            "margin-right" -> result.copy(marginRight = parseLength(value))
            "margin" -> parseMarginShorthand(value)?.let { sides ->
                result.copy(
                    marginTop = sides.top,
                    marginRight = sides.right,
                    marginBottom = sides.bottom,
                    marginLeft = sides.left,
                )
            } ?: result
            "border" -> result.withBorder(parseBorderShorthand(value))
            "border-top" -> result.copy(borderTop = parseBorderShorthand(value))
            "border-right" -> result.copy(borderRight = parseBorderShorthand(value))
            "border-bottom" -> result.copy(borderBottom = parseBorderShorthand(value))
            "border-left" -> result.copy(borderLeft = parseBorderShorthand(value))
            "border-top-width" -> result.copy(borderTop = result.borderTop.mergeWidth(parseBorderWidthValue(value)))
            "border-right-width" -> result.copy(borderRight = result.borderRight.mergeWidth(parseBorderWidthValue(value)))
            "border-bottom-width" -> result.copy(borderBottom = result.borderBottom.mergeWidth(parseBorderWidthValue(value)))
            "border-left-width" -> result.copy(borderLeft = result.borderLeft.mergeWidth(parseBorderWidthValue(value)))
            "border-top-color" -> result.copy(borderTop = result.borderTop.mergeColor(value))
            "border-right-color" -> result.copy(borderRight = result.borderRight.mergeColor(value))
            "border-bottom-color" -> result.copy(borderBottom = result.borderBottom.mergeColor(value))
            "border-left-color" -> result.copy(borderLeft = result.borderLeft.mergeColor(value))
            "border-radius" -> result.copy(borderRadius = parseBorderRadius(value))
            else -> result
        }
    }
    return result
}

/** `border` 축약형을 이 엔진이 보존하는 모든 변에 그대로 복사한 값. */
private fun CssDeclarations.withBorder(border: CssBorder?): CssDeclarations = copy(
    borderTop = border ?: borderTop,
    borderRight = border ?: borderRight,
    borderBottom = border ?: borderBottom,
    borderLeft = border ?: borderLeft,
)

/** 이 테두리의 현재 너비 위에 [width]를 층층이 쌓은 것. 둘 중 하나라도 있으면 결과가 존재한다. */
private fun CssBorder?.mergeWidth(width: CssLength?): CssBorder? = if (width == null && this == null) null else CssBorder(
    width = width ?: this?.width,
    color = this?.color,
)

/** 이 테두리의 현재 색상 위에 [color]를 층층이 쌓은 것. 둘 중 하나라도 있으면 결과가 존재한다. */
private fun CssBorder?.mergeColor(color: String?): CssBorder? = if (color == null && this == null) null else CssBorder(
    width = this?.width,
    color = color ?: this?.color,
)

/**
 * 하나의 CSS 길이 값을 [CssLength]로 파싱한다. [value]에 숫자가 없거나 인식할 수 없는 단위를
 * 가지면 null.
 *
 * 맨 `0`은 속성이 무엇이든 CSS에서 길이다 — 거의 모든 EPUB이 리셋(`margin: 0`)을 쓰는 방식이다 —
 * 그래서 "설정되지 않음"이 아니라 0으로 해석된다. 단위 없는 다른 숫자는 이 엔진이 어떤 것도 크기
 * 지정할 수 있는 길이가 아니므로 버려진다.
 *
 * @param value 원시 선언 값, 예: `"90%"`, `"1.5em"`, `"0"`.
 */
private fun parseLength(value: String): CssLength? {
    val trimmed = value.trim()
    trimmed.toFloatOrNull()?.let { bare -> return if (bare == 0f) CssLength.Px(0f) else null }
    val match = CssLengthRegex.find(trimmed) ?: return null
    val number = match.groupValues[1].toFloatOrNull() ?: return null
    return when (match.groupValues[2].lowercase()) {
        "%" -> CssLength.Percent(number / 100f)
        "em", "rem" -> CssLength.Em(number)
        "px" -> CssLength.Px(number)
        "pt" -> CssLength.Px(number * PxPerPoint)
        else -> null
    }
}

/** 1포인트에 해당하는 CSS 픽셀 값: `1pt = 1/72in`을 기준 `96dpi` 픽셀에 대해 계산. */
private const val PxPerPoint = 96f / 72f

/** `line-height: 1.6`은 단위가 없으며 요소 자신의 폰트 크기에 대한 배수를 의미한다. */
private fun parseLineHeight(value: String): CssLineHeight? =
    value.trim().toFloatOrNull()?.let { CssLineHeight.Factor(it) }
        ?: parseLength(value)?.let { CssLineHeight.Length(it) }

/** `margin` 축약형이 1~4개 값 형태로 해석되는 네 변. */
internal data class CssMarginSides(
    val top: CssLength? = null,
    val right: CssLength? = null,
    val bottom: CssLength? = null,
    val left: CssLength? = null,
)

/** `margin` 축약형의 모든 변을, CSS가 정의하는 1~4개 값 형태대로 펼친 것. */
private fun parseMarginShorthand(value: String): CssMarginSides? {
    val parts = value.trim().split(CssWhitespaceRegex).filter(String::isNotEmpty).map(::parseLength)
    return when (parts.size) {
        1 -> CssMarginSides(parts[0], parts[0], parts[0], parts[0])
        2 -> CssMarginSides(parts[0], parts[1], parts[0], parts[1])
        3 -> CssMarginSides(parts[0], parts[1], parts[2], parts[1])
        4 -> CssMarginSides(parts[0], parts[1], parts[2], parts[3])
        else -> null
    }
}

/** `border` 축약형에서 보존된 너비/색상. 존재 여부만 빼고 스타일은 무시한다. */
private fun parseBorderShorthand(value: String): CssBorder? {
    val parts = value.trim().split(CssWhitespaceRegex).filter(String::isNotEmpty)
    if (parts.isEmpty()) return null
    var width: CssLength? = null
    var color: String? = null
    var style: String? = null
    parts.forEach { part ->
        if (width == null) width = parseBorderWidthValue(part)
        if (style == null) style = parseBorderStyleKeyword(part)
        if (color == null && style != part.lowercase()) {
            if (parseBorderWidthValue(part) == null) color = part
        }
    }
    if (style == "none" || style == "hidden") return CssBorder(width = CssLength.Px(0f), color = color)
    if (style != null && width == null) width = CssLength.Px(3f)
    return if (width == null && color == null) null else CssBorder(width = width, color = color)
}

/** 테두리 너비 값 하나. 단위 없는 0이라는 border 전용 특수 케이스도 포함한다. */
private fun parseBorderWidthValue(value: String): CssLength? =
    parseBorderWidthKeyword(value)
        ?: value.trim().takeIf { it == "0" || it == "+0" || it == "-0" || it == "0.0" }?.let { CssLength.Px(0f) }
        ?: parseLength(value)

/** 독서 시스템이 전통적으로 매핑하는 픽셀 값으로서의 키워드 테두리 너비. */
private fun parseBorderWidthKeyword(value: String): CssLength? = when (value.lowercase()) {
    "thin" -> CssLength.Px(1f)
    "medium" -> CssLength.Px(3f)
    "thick" -> CssLength.Px(5f)
    else -> null
}

/** 이 파서가 색상으로 오독하지 않기 위해서만 인식하는 테두리 스타일들. */
private fun parseBorderStyleKeyword(value: String): String? = when (value.lowercase()) {
    "none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset" -> value.lowercase()
    else -> null
}

/** `border-radius`의 첫 번째 반경 구성요소; 슬래시로 구분된 타원형 반경은 하나로 접힌다. */
private fun parseBorderRadius(value: String): CssLength? =
    value.substringBefore('/').trim().split(CssWhitespaceRegex).firstOrNull()?.let(::parseLength)

/**
 * 하나의 `@font-face` 본문을, 그것이 정의하는 패밀리와 이 리더가 나중에 열 수 있는 첫 번째
 * 상대/임베디드 `url(...)` 소스로 파싱한다.
 */
private fun parseFontFace(body: String, cssPath: String?): CssFontFace? {
    val declarations = parseCssDeclarations(body)
    val family = declarations.fontFamily?.let(::splitFontFamilies)?.firstOrNull()?.trimQuotes()?.takeIf(String::isNotEmpty) ?: return null
    val srcHref = FontFaceUrlRegex.find(body)?.groupValues?.get(1)?.trimQuotes()?.let { resolveContainerHref(cssPath, it) }
    return CssFontFace(familyName = family, srcHref = srcHref)
}

/** 하나의 `font-family` 값을 쉼표로 나누어 개별 패밀리 이름으로 다듬은 것. */
private fun splitFontFamilies(value: String): List<String> =
    value.split(',').map(String::trim).map(String::trimQuotes).filter(String::isNotEmpty)

/** 감싸는 따옴표 한 쌍이 제거된, 인용되었을 수도 있는 이 CSS 문자열 토큰. */
private fun String.trimQuotes(): String = trim().removeSurrounding("\"").removeSurrounding("'")

/** [EpubCss.resolvedFontHref]가 패밀리 이름을 `@font-face`와 매칭할 때 쓰는 소문자 키 형태. */
private fun String.normalizeFontFamilyKey(): String = trimQuotes().lowercase()

/** 끝의 `!important` 하나가 제거되고 나머지는 그대로 보존된 선언 값. */
private fun String.stripImportant(): String = replace(ImportantSuffixRegex, "").trim()

/** 모든 CSS 블록 주석이 지워진 [css]. 주석 처리된 규칙이 실제 규칙으로 파싱되는 일이 없도록 한다. */
private fun stripCssComments(css: String): String = css.replace(CssCommentRegex, " ")

/** [stripCssComments]가 지워버릴 CSS 블록 주석을 매칭한다. 줄바꿈에 걸쳐서도 매칭된다. */
private val CssCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
/**
 * 선택자를 그 복합 부분들을 구분하는 결합자 어느 것이든 기준으로 나눈다; 모든 결합자는 일반 자손
 * 결합자로 읽힌다 — [CssSelector.matches] 참고.
 */
private val CssCombinatorRegex = Regex("""[\s>+~]+""")
/**
 * 이 매처가 관찰할 수 있는 상태라기보다 요소가 평소 어떻게 보이는지를 설명하는 의사 클래스들이며,
 * 그래서 규칙 전체를 희생시키는 대신 복합 부분에서만 제거된다.
 *
 * `a:link`와 `a:visited`는 합쳐서 페이지의 모든 링크를 커버하므로, `a:link { text-decoration: none }`을
 * 쓰는 책은 자신의 링크가 밑줄이 없다고 말하는 것이다. 그 규칙을 버리는 것은 — 이는 이것을 판단할 수
 * 없는 어떤 선택자에도 일어나는 일인데 — 책이 가진 모든 링크에 밑줄을 긋게 된다.
 */
private val StatelessPseudoClassRegex = Regex(":(link|visited)", RegexOption.IGNORE_CASE)

/** 복합 부분에서 클래스 이름 하나를 캡처한다, 예: `.note`에서 `note`. */
private val CssClassNameRegex = Regex("""\.([\w-]+)""")
/** 복합 부분에서 id를 캡처한다, 예: `#lead`에서 `lead`. */
private val CssIdRegex = Regex("""#([\w-]+)""")
/**
 * 길이 값을 매칭하고 그 숫자와 단위를 캡처한다; margin은 음수일 수 있으므로 앞의 `-`가 허용된다.
 */
private val CssLengthRegex = Regex("""(-?[0-9.]+)\s*(%|em|rem|px|pt)""", RegexOption.IGNORE_CASE)
/** `margin`/`border-radius` 같은 축약형을 CSS 공백 기준으로 나눈다. */
private val CssWhitespaceRegex = Regex("""\s+""")
/** 하나의 선언 값 끝에 있는 단일 `!important` 마커. */
private val ImportantSuffixRegex = Regex("""\s*!important\s*$""", RegexOption.IGNORE_CASE)
/** `@font-face src` 선언에서 하나의 `url(...)` 페이로드를 캡처한다. */
private val FontFaceUrlRegex = Regex("""url\(([^)]+)\)""", RegexOption.IGNORE_CASE)
