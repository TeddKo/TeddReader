package com.tedd.teddreader.core.common.model

import kotlinx.serialization.Serializable

/**
 * 문서 텍스트가 지닌 구조로, 리더가 검색·북마크·독서 위치에 이미 사용하는 같은 문자 오프셋 위에 놓인다. 블록은 텍스트 구간의 의미를 나타낼 뿐 텍스트 자체를 소유하지 않으므로, 페이지 나누기와 진행률은 하나의 평면 문자열을 계속 기준으로 삼는다.
 */
@Serializable
enum class ReaderBlockKind {
    PARAGRAPH,
    HEADING,
    QUOTE,
    LIST_ITEM,
    PREFORMATTED,
    CONTAINER,
    IMAGE,
    COVER_IMAGE,
    TABLE_CELL,
    TABLE_HEADER_CELL,
    SEPARATOR,
}

/**
 * [ReaderSpan]이 적용하는, 책이 블록 내부에 요청할 수 있는 인라인 강조이다.
 *
 * 리더가 이미 그릴 수 있는 의미론적 인라인 형태의 닫힌 집합이다. 리더 소유 타입으로 표현할 수 있는 추가 인라인 CSS는 이 열거형을 원시 스타일시트 어휘로 확장하지 않고 [ReaderSpan.styleDelta]를 통해 함께 전달된다.
 */
@Serializable
enum class ReaderInlineStyle {
    BOLD,
    ITALIC,
    UNDERLINE,
    STRIKETHROUGH,
    MONOSPACE,
    SUPERSCRIPT,
    SUBSCRIPT,
    LINK,
}

/**
 * 책이 요청할 수 있으며 이 리더가 실제로 제공할 수 있는 일반 글꼴 패밀리이다.
 */
@Serializable
enum class ReaderFontFamily {
    SERIF,
    SANS_SERIF,
    MONOSPACE,
}

/**
 * 출판사 스타일에서 플로트 이미지가 붙는 인라인 가장자리이다.
 */
@Serializable
enum class ReaderFloat {
    START,
    END,
}

/**
 * 출판사가 요청한 상자 테두리의 한 변이다.
 */
@Serializable
data class ReaderBorder(
    val widthPx: Float? = null,
    val color: ReaderColor? = null,
) {
    init {
        require(widthPx == null || widthPx >= 0f) { "Border width must be non-negative." }
    }
}

/**
 * 렌더러가 나중에도 반영할 수 있는 간결한 상자 스타일이다.
 */
@Serializable
data class ReaderBoxStyle(
    val backgroundColor: ReaderColor? = null,
    val borderTop: ReaderBorder? = null,
    val borderRight: ReaderBorder? = null,
    val borderBottom: ReaderBorder? = null,
    val borderLeft: ReaderBorder? = null,
    /**
     * 닫힌 범위 0..100의 CSS/Compose 백분율로 나타낸 모서리 둥글기이다.
     */
    val borderRadiusPercent: Float? = null,
) {
    init {
        require(borderRadiusPercent == null || borderRadiusPercent in 0f..100f) {
            "Border radius percent must be in 0..100."
        }
    }

    fun isEmpty(): Boolean = this == Empty

    companion object {
        val Empty = ReaderBoxStyle()
    }
}

/**
 * 책 자체의 스타일시트가 블록 또는 이 타입을 재사용할 만큼 좁은 인라인 구간의 모양을 리더 자체 글꼴에 대한 상대 단위로 나타낸다.
 *
 * 여기의 모든 값은 의도적으로 상대적이다. 리더의 글꼴 크기와 테마가 우선하며, 책은 그 주변을 조정한다. 스타일시트의 절대 크기나 색상은 독자가 선택한 크기 및 읽고 있는 테마와 충돌하기 때문이다.
 *
 * @property fontScale 리더 글꼴 크기의 배수(예: `font-size: 1.4em`). 지정하지 않으면 `null`이다.
 * @property bold 책이 굵게 표시하도록 요청하는지 나타내며, 아무것도 지정하지 않으면 `null`이다.
 * @property italic 책이 기울임을 요청하는지 나타내며, 아무것도 지정하지 않으면 `null`이다.
 * @property fontFamily 책이 요청하는 일반 패밀리이며, 아무것도 지정하지 않으면 `null`이다.
 * @property lineHeightScale 글꼴 크기의 배수(예: `line-height: 1.7em`). 지정하지 않으면 `null`이다.
 * @property textIndentEm em 단위의 첫 줄 들여쓰기(예: `text-indent: 1em`). 지정하지 않으면 `null`이다.
 * @property marginTopEm em 단위의 블록 위쪽 공간(예: `margin-top: 0.5em`). 지정하지 않으면 `null`이다.
 * @property marginBottomEm em 단위의 블록 아래쪽 공간. 지정하지 않으면 `null`이다. 두 문단 사이 간격을 결정하므로, 책이 `margin-bottom: 10px`을 지정하면 한 줄 전체가 아니라 그 간격을 사용한다.
 * @property marginStartEm em 단위의 인라인 시작 공간. 지정하지 않으면 `null`이다.
 * @property marginEndEm em 단위의 인라인 끝 공간. 지정하지 않으면 `null`이다.
 * @property paddingTopEm em 단위로 블록의 텍스트 위쪽 내부에 두는 공간. 지정하지 않으면 `null`이다.
 * @property paddingBottomEm em 단위로 블록의 텍스트 아래쪽 내부에 두는 공간. 지정하지 않으면 `null`이다.
 * @property paddingStartEm em 단위로 블록의 텍스트 앞쪽 내부에 두는 공간. 지정하지 않으면 `null`이다. 책은 이 값으로 인용문을 들여쓰며, 리더가 이를 버리면 인용문이 본문과 같은 위치에 붙는다.
 * @property paddingEndEm em 단위로 블록의 텍스트 뒤쪽 내부에 두는 공간. 지정하지 않으면 `null`이다.
 * @property underline 책이 밑줄을 요청하는지 나타낸다. `false`는 책이 링크에 밑줄이 없다고 명시하는 실제 값이며, `null`은 아무것도 지정하지 않았다는 뜻이다.
 * @property lineThrough 책이 취소선을 요청하는지 나타내며, 아무것도 지정하지 않으면 `null`이다.
 * @throws IllegalArgumentException [fontScale] 또는 [lineHeightScale]이 양수가 아니거나 세로 여백이 음수인 경우.
 */
@Serializable
data class ReaderBlockStyle(
    /**
     * 리더 글꼴 크기의 배수이다(예: `font-size: 1.4em`).
     */
    val fontScale: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val fontFamily: ReaderFontFamily? = null,
    val fontFamilyName: String? = null,
    val fontHref: String? = null,
    /**
     * 글꼴 크기의 배수이다(예: `line-height: 1.7em`).
     */
    val lineHeightScale: Float? = null,
    /**
     * em 단위의 첫 줄 들여쓰기이다(예: `text-indent: 1em`).
     */
    val textIndentEm: Float? = null,
    /**
     * 책이 이 블록 위에 요청하는 em 단위 공간이다. 아무것도 지정하지 않으면 `null`이다.
     */
    val marginTopEm: Float? = null,
    /**
     * 책이 이 블록 아래에 요청하는 em 단위 공간이다. 아무것도 지정하지 않으면 `null`이다.
     */
    val marginBottomEm: Float? = null,
    /**
     * 책이 이 블록의 인라인 앞쪽에 요청하는 em 단위 공간이다. 아무것도 지정하지 않으면 `null`이다.
     */
    val marginStartEm: Float? = null,
    /**
     * 책이 이 블록의 인라인 뒤쪽에 요청하는 em 단위 공간이다. 아무것도 지정하지 않으면 `null`이다.
     */
    val marginEndEm: Float? = null,
    /**
     * 블록 자체 텍스트 위쪽의 em 단위 내부 공간이다. 책이 아무것도 지정하지 않으면 `null`이다.
     */
    val paddingTopEm: Float? = null,
    /**
     * 블록 자체 텍스트 아래쪽의 em 단위 내부 공간이다. 책이 아무것도 지정하지 않으면 `null`이다.
     */
    val paddingBottomEm: Float? = null,
    /**
     * 블록 자체 텍스트 앞쪽의 em 단위 내부 공간으로, 인용문을 들여쓴다. 지정하지 않으면 `null`이다.
     */
    val paddingStartEm: Float? = null,
    /**
     * 블록 자체 텍스트 뒤쪽의 em 단위 내부 공간이다. 책이 아무것도 지정하지 않으면 `null`이다.
     */
    val paddingEndEm: Float? = null,
    /**
     * 이 블록의 텍스트 앞에 레이아웃되는 전체 인라인 시작 공간을 em 단위로 나타낸 값이다. 자체 시작 여백과 안쪽 여백에 파서가 누적한 모든 블록 수준 조상의 값을 더한다. 아무것도 지정하지 않으면 `null`이다. 렌더러가 실제 들여쓰기에 사용하는 해석된 값이며, 위의 각 변의 여백/안쪽 여백 필드는 상자를 그리기 위해 블록 자체 값으로 남는다.
     */
    val insetStartEm: Float? = null,
    /**
     * [insetStartEm]에 대응하는 인라인 끝 값이다. 아무것도 지정하지 않으면 `null`이다.
     */
    val insetEndEm: Float? = null,
    /**
     * 책이 밑줄을 요청하는지 나타내며, 장식에 관해 아무것도 지정하지 않으면 `null`이다.
     */
    val underline: Boolean? = null,
    /**
     * 책이 취소선을 요청하는지 나타내며, 장식에 관해 아무것도 지정하지 않으면 `null`이다.
     */
    val lineThrough: Boolean? = null,
    val foregroundColor: ReaderColor? = null,
    val boxStyle: ReaderBoxStyle? = null,
) {
    init {
        require(fontScale == null || fontScale > 0f) { "fontScale must be positive." }
        require(lineHeightScale == null || lineHeightScale > 0f) { "lineHeightScale must be positive." }
        require(marginTopEm == null || marginTopEm >= 0f) { "marginTopEm must be non-negative." }
        require(marginBottomEm == null || marginBottomEm >= 0f) { "marginBottomEm must be non-negative." }
    }

    /**
     * 책의 스타일시트가 여기에 아무것도 지정하지 않아 [Empty]와 구별되지 않는지 나타낸다.
     */
    fun isEmpty(): Boolean = this == Empty

    /**
     * 스타일을 지정하지 않은 블록마다 새 인스턴스를 만들지 않고 제공하는 공유 인스턴스 [Empty]를 보관한다.
     */
    companion object {
        val Empty = ReaderBlockStyle()
    }
}

/**
 * 스팬이 자신을 감싼 컨텍스트와의 *차이*로 지니는 인라인 스타일이며, 절대값이 아니다.
 *
 * 스팬은 블록 안에, 그리고 다른 스팬 안에 중첩되며 렌더러는 해당 위치에 이미 적용된 값 위에 이 값을 적용한다. em 글꼴 크기는 바깥 크기에 곱해진다. 상속받은 전체 스타일을 다시 지정한 스팬은 블록이 이미 적용한 모든 값을 또 적용하여 `0.9em` 래퍼의 텍스트가 `0.81`로 표시됐다. 이 타입은 그 계약을 주석이 아닌 구조로 만든다. 차이에 안전한 속성만 존재하며 절대 길이(여백, 삽입 여백, 줄 높이, 들여쓰기)는 구조상 스팬에서 표현할 수 없다.
 *
 * @property fontScale 리더의 기준이 아니라 스팬 위치의 *바깥* 글꼴 크기에 대한 배수이다. 스팬이 크기를 바꾸지 않으면 `null`이다.
 * @property bold/italic/fontFamily/fontFamilyName/fontHref/foregroundColor 바깥 값에 대한 재정의이다. `null`이면 바깥 값을 유지한다.
 * @property underline 이 스팬 전체에 장식을 그릴지 나타낸다. `false`는 책이 링크의 밑줄을 끄는 실제 값이며, `null`이면 바깥 장식을 유지한다.
 * @property lineThrough [underline]과 같은 조건의 취소선이다.
 */
@Serializable
data class ReaderSpanStyle(
    val fontScale: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val fontFamily: ReaderFontFamily? = null,
    val fontFamilyName: String? = null,
    val fontHref: String? = null,
    val underline: Boolean? = null,
    val lineThrough: Boolean? = null,
    val foregroundColor: ReaderColor? = null,
) {
    init {
        require(fontScale == null || fontScale > 0f) { "fontScale must be positive." }
    }

    /**
     * 이 차이가 아무것도 바꾸지 않아 [Empty]와 구별되지 않는지 나타낸다.
     */
    fun isEmpty(): Boolean = this == Empty

    /**
     * 아무 효과도 없는 공유 인스턴스 [Empty]를 보관한다.
     */
    companion object {
        val Empty = ReaderSpanStyle()
    }
}

/**
 * 책의 스타일시트가 지정했을 때 블록의 줄을 정렬하는 방식이다. 블록의 `null`은 리더 자체 기본값을 유지한다는 뜻이다.
 */
@Serializable
enum class ReaderTextAlign {
    START,
    CENTER,
    END,
    JUSTIFY,
}

/**
 * 문서의 평면 텍스트에서 주소를 지정하는 블록 내부의 인라인 구간 하나이다.
 *
 * @property range 절대 문서 오프셋으로 나타낸 구간의 범위.
 * @property style 이 구간에 적용할 강조이며, 의미론적 태그가 없는 순수 CSS 스팬이면 `null`이다.
 * @property href 링크 대상이며 [ReaderInlineStyle.LINK]에는 필수이고 그 외에는 `null`이다.
 * @property styleDelta [style]만으로 충분하지 않을 때 스팬과 함께 전달되는 추가 CSS 유래 스타일로, 항상 바깥 컨텍스트에 대한 [ReaderSpanStyle] 차이이다.
 * @throws IllegalArgumentException 링크 스팬에 [href]가 없는 경우.
 */
@Serializable
data class ReaderSpan(
    val range: TextRange,
    val style: ReaderInlineStyle? = null,
    val href: String? = null,
    val styleDelta: ReaderSpanStyle? = null,
) {
    init {
        require(style != ReaderInlineStyle.LINK || href != null) { "A link span must carry an href." }
        require(
            style != null || styleDelta?.isEmpty() == false,
        ) { "A span must carry a semantic style or non-empty styleDelta." }
    }
}

/**
 * 문서의 구조 조각 하나로 문단, 제목, 그림 또는 표 셀 등을 [kind]로 나타내고, 자신이 덮는 평면 텍스트 범위로 주소를 지정한다.
 *
 * 블록은 텍스트를 소유하지 않는다. 검색·북마크·독서 위치가 사용하는 같은 절대 오프셋을 가리키므로, 페이지 나누기나 진행률이 스타일을 알 필요 없이 문서에 스타일을 추가할 수 있다. 텍스트는 하나의 평면 문자열로 남고 블록은 그 위의 계층이다.
 *
 * 대부분의 필드는 `null` 허용이다. 블록은 자신의 종류와 책의 스타일시트가 실제로 지정한 값만 지니기 때문이다. `init`은 함께 성립해야 하는 쌍, 특히 그림이 항상 파일 위치를 알아야 한다는 조건을 강제한다.
 *
 * 이미지 필드를 하나의 크기로 미리 해석하지 않고 분리한 이유는 페이지 레이아웃 대상 열과 페이지에 따라 답이 달라지기 때문이다. 이 필드들을 결합하는 유일한 위치인 [readerImageSize]를 참고한다.
 */
@Serializable
data class ReaderBlock(
    /**
     * 이 블록이 문단, 제목, 그림, 표 셀 중 무엇인지 나타내며, 아래 필드 중 적용할 항목을 결정한다.
     */
    val kind: ReaderBlockKind,
    /**
     * 이 블록이 덮는 평면 텍스트 범위로, 검색·북마크·독서 위치가 이미 사용하는 같은 절대 문서 오프셋이다.
     */
    val range: TextRange,
    /**
     * 제목 수준 1..6, 1부터 시작하는 목록 중첩 깊이 또는 1부터 시작하는 `CONTAINER` 중첩 깊이이다. 종류에 수준이 없으면 0이다.
     */
    val level: Int = 0,
    /**
     * 이 블록 자체 텍스트 안의 인라인 강조 구간이다. 책이 요청하지 않으면 비어 있다.
     */
    val spans: List<ReaderSpan> = emptyList(),
    /**
     * 책의 스타일시트가 지정했을 때 이 블록의 줄을 정렬하는 방식이다. `null`이면 리더 자체 기본값을 유지한다.
     */
    val align: ReaderTextAlign? = null,
    /**
     * [ReaderBlockKind.IMAGE]에서 컨테이너 내부 이미지의 해석된 경로이다.
     */
    val imageHref: String? = null,
    /**
     * 이미지의 `alt` 텍스트 또는 순서 있는 목록 항목의 표식이다.
     */
    val label: String? = null,
    /**
     * [ReaderBlockKind.TABLE_CELL]과 [ReaderBlockKind.TABLE_HEADER_CELL]에서 이 셀이 표 안에 위치한 0부터 시작하는 행이다. 다른 모든 종류에서는 `null`이다.
     */
    val tableRow: Int? = null,
    /**
     * [tableRow] 안에서 0부터 시작하는 열이다. 표 셀 이외의 모든 종류에서는 `null`이다.
     */
    val tableColumn: Int? = null,
    /**
     * [ReaderBlockKind.IMAGE]와 [ReaderBlockKind.COVER_IMAGE]에서 원본 이미지의 너비를 높이로 나눈 값이다.
     */
    val imageAspectRatio: Float? = null,
    /**
     * 아무것도 너비를 지정하지 않았을 때 사용하는 원본 이미지의 CSS 픽셀 단위 고유 너비이다.
     */
    val imageNaturalWidthPx: Int? = null,
    /**
     * 문서 자체 스타일시트가 이미지에 부여한 너비를 텍스트 열의 비율로 나타낸 값이다.
     */
    val imageWidthPercent: Float? = null,
    /**
     * 문서 자체 스타일시트가 이미지에 부여한 em 단위 너비이다.
     */
    val imageWidthEm: Float? = null,
    /**
     * 문서가 이미지에 부여한 플로트 배치이며, 플로트하지 않으면 `null`이다.
     */
    val float: ReaderFloat? = null,
    /**
     * 책의 스타일시트가 지정한 이 블록의 모양이며, 아무것도 지정하지 않으면 `null`이다.
     */
    val style: ReaderBlockStyle? = null,
    /**
     * 전체 페이지 표면을 그려야 하는 `html`/`body` 컨테이너 블록에만 `true`이다.
     */
    val isPageContainer: Boolean = false,
) {
    init {
        require(level >= 0) { "Block level must be non-negative." }
        require(
            (kind != ReaderBlockKind.IMAGE && kind != ReaderBlockKind.COVER_IMAGE) || imageHref != null,
        ) { "An image block must carry an href." }
        require(tableRow == null || tableRow >= 0) { "Table row must be positive." }
        require(tableColumn == null || tableColumn >= 0) { "Table column must be positive." }
        require(imageAspectRatio == null || imageAspectRatio > 0f) { "Image aspect ratio must be positive." }
        require(imageNaturalWidthPx == null || imageNaturalWidthPx > 0) { "Image natural width must be positive." }
        require(imageWidthPercent == null || imageWidthPercent > 0f) { "Image width percent must be positive." }
        require(imageWidthEm == null || imageWidthEm > 0f) { "Image width em must be positive." }
    }
}

/**
 * 측정과 렌더링이 서로 다를 수 없도록 em 단위로 나타낸 이미지의 그리기 크기이다.
 *
 * @property widthEm 상자의 em 단위 너비.
 * @property heightEm 상자의 em 단위 높이.
 */
data class ReaderImageSize(val widthEm: Float, val heightEm: Float)

/**
 * 읽기 시스템과 같은 방식으로 이미지 하나를 배치한다.
 *
 * 모든 재배치 가능한 읽기 시스템이 사용하는 규칙을 따른다. Readium 자체 스타일시트는 `img, svg, video { object-fit: contain; width: auto; height: auto; max-width: 100%;
 * max-height: 95vh !important; break-inside: avoid }`, readium-shared-js는 `max-width: 98%; max-height: 98%; height: auto; width: auto`로 지정하며, foliate-js도 찾은 모든 `img, svg, video`에 같은 두 최댓값과 `object-fit: contain`을 설정한다. 공통 의미는 그림을 실제 크기, 즉 책의 스타일시트가 지정한 너비 또는 없으면 그림 자체의 고유 너비로 그리되, 열과 페이지에 맞추기 위해 줄일 뿐 절대 그 크기까지 늘리지 않는다는 것이다. 모든 그림을 열 전체로 강제하면 가는 선이 두꺼운 띠가 되고 작은 로고가 포스터가 된다.
 *
 * 페이지 상한은 전체 페이지가 아니라 [MaxImagePageHeightFraction]이다. 페이지의 마지막 가는 공간까지 이미지를 허용하면 이미지를 담는 줄 상자의 공간이 없어져 이미지가 별도 페이지로 밀리거나 가장자리에서 잘리기 때문이다. 이 상한에 걸리면 비율을 유지하고, 더 짧아진 상자에 필요 없는 너비를 텍스트에 돌려준다.
 *
 * 비율을 읽지 못한 이미지는 페이지 전체를 할당하지 않고 정사각형으로 처리한다. 상자는 텍스트가 주변을 배치할 영역일 뿐 그림은 그릴 때 실제 형태를 유지하므로, 측정할 수 없는 이미지에 페이지 전체를 할당하면 작은 삽화가 빈 공간에 고립되고 주변 텍스트가 페이지 밖으로 밀려난다.
 *
 * @receiver 크기를 정할 이미지 또는 구분선 블록.
 * @param columnWidthEm 상자가 들어가야 하는 텍스트 열.
 * @param maxHeightEm 95% 상한을 적용하기 전 페이지 높이.
 * @param emInPx 1em에 해당하는 CSS 픽셀 수로, 고유 픽셀 너비를 em으로 변환한다.
 * @return 텍스트가 주변에 배치되는 상자로, 어느 방향도 [MinReaderImageEm]보다 작지 않다.
 */
fun ReaderBlock.readerImageSize(
    columnWidthEm: Float,
    maxHeightEm: Float,
    emInPx: Float,
): ReaderImageSize {
    val column = columnWidthEm.coerceAtLeast(MinReaderImageEm)
    if (kind == ReaderBlockKind.SEPARATOR) return ReaderImageSize(column, SeparatorHeightEm)

    val page = (maxHeightEm * MaxImagePageHeightFraction).coerceAtLeast(MinReaderImageEm)
    val declaredEm = imageWidthEm
        ?: imageWidthPercent?.let { column * it }
        ?: imageNaturalWidthPx?.takeIf { emInPx > 0f }?.let { it / emInPx }
    var width = (declaredEm ?: column).coerceIn(MinReaderImageEm, column)
    val ratio = imageAspectRatio?.takeIf { it > 0f }
    var height = if (ratio != null) width / ratio else width
    if (height > page) {
        if (ratio != null) width = (page * ratio).coerceAtMost(column)
        height = page
    }
    return ReaderImageSize(width.coerceAtLeast(MinReaderImageEm), height.coerceAtLeast(MinReaderImageEm))
}

/**
 * 책이 0으로 지정한 그림도 그릴 수 있는 공간을 차지하도록 모든 이미지 상자에 적용하는 하한이다.
 */
private const val MinReaderImageEm = 0.05f

/**
 * Readium 스타일시트가 재배치 가능 텍스트의 모든 이미지에 적용하는 상한인 `max-height: 95vh`이다.
 */
private const val MaxImagePageHeightFraction = 0.95f

/**
 * 렌더러가 해당 위치에 두는 구분선과 일치하는 가로 구분선의 그리기 높이이다.
 */
private const val SeparatorHeightEm = 1.25f

/**
 * 렌더러가 종류를 나열하지 않고 셀을 행으로 묶을 수 있도록 이 종류가 표에 속하는지 나타낸다.
 *
 * @receiver 검사할 종류.
 * @return [ReaderBlockKind.TABLE_CELL] 또는 [ReaderBlockKind.TABLE_HEADER_CELL]이면 `true`.
 */
fun ReaderBlockKind.isTableCell(): Boolean =
    this == ReaderBlockKind.TABLE_CELL || this == ReaderBlockKind.TABLE_HEADER_CELL

/**
 * 블록이 텍스트 이외의 무언가를 그려 읽을 수 있는 문자를 지니지 않으면 `true`이다.
 *
 * @receiver 검사할 종류.
 * @return 텍스트 대신 무언가를 그리는 종류인 이미지, 표지 이미지, 구분선이면 `true`.
 */
fun ReaderBlockKind.isStandalone(): Boolean =
    this == ReaderBlockKind.IMAGE || this == ReaderBlockKind.COVER_IMAGE || this == ReaderBlockKind.SEPARATOR

/**
 * 문서의 평면 텍스트에서 그림 하나가 차지하는 문자 U+FFFC OBJECT REPLACEMENT CHARACTER이며, 이름 그대로의 의미다. 그림을 두 블록 사이가 아니라 텍스트 안에 두므로 `<img>`는 HTML이 배치한 대로 작성된 문장 안에 머문다.
 */
const val ReaderObjectReplacementChar: Char = '￼'

/**
 * 그림을 제외하고 읽었을 때 [this]가 비어 있으면 `true`이다.
 *
 * @receiver 검사할 텍스트.
 * @return 공백과 그림 대체 문구만 포함하면 `true`.
 */
fun String.isBlankIgnoringObjects(): Boolean =
    all { char -> char == ReaderObjectReplacementChar || char.isWhitespace() }

/**
 * 문장 안에 놓인 것과 달리 한 줄을 단독으로 차지하는 그림과 구분선이다.
 *
 * `<img>`는 HTML의 인라인 콘텐츠이다. 외자 글리프나 아이콘은 작성된 줄에 속하며 어떤 읽기 시스템도 이를 문단 밖으로 옮기지 않는다. 블록 안에 그림만 있으면 이를 감싸는 문단이 없으며, 바로 이 점이 플레이트를 만든다.
 *
 * *텍스트를 지닌* 블록만 감싼 것으로 간주한다. [ReaderBlockKind.CONTAINER]는 래퍼로 장식과 간격을 소유할 뿐 본문 한 줄은 소유하지 않으며, 책은 흔히 플레이트를 그 안에 넣는다(`<div class="frame"><img/></div>`). 래퍼를 텍스트로 계산하면 이런 모든 플레이트가 인라인 글리프로 낮아져 자체 중앙 정렬 줄을 잃는다. 페이지 컨테이너로 기록된 스타일 있는 `body`도 마찬가지로 페이지의 모든 내용을 감싸므로 내부 그림에 관해 아무것도 증명하지 못한다.
 *
 * @receiver 고려 중인 텍스트 구간의 모든 블록.
 * @return 텍스트 블록이 감싸지 않는 독립 블록, 즉 문장 안의 그림이 아닌 플레이트.
 */
fun List<ReaderBlock>.standaloneBlocks(): List<ReaderBlock> {
    val textRanges = filter { !it.kind.isStandalone() && it.kind != ReaderBlockKind.CONTAINER }.map { it.range }
    if (textRanges.isEmpty()) return filter { it.kind.isStandalone() }
    return filter { block ->
        block.kind.isStandalone() &&
            textRanges.none { range -> range.start <= block.range.start && range.end >= block.range.end }
    }
}

/**
 * [시작, 끝)와 겹치는 블록으로, 페이지가 실제 표시하는 구조만 렌더링할 수 있게 한다.
 *
 * @receiver 필터링할 블록.
 * @param start 범위의 첫 오프셋으로, 포함된다.
 * @param end 마지막 오프셋 다음 값.
 * @return 해당 범위와 겹치는 블록으로, 범위 안에 있는 너비 0인 블록도 포함한다.
 */
fun List<ReaderBlock>.blocksIn(start: Long, end: Long): List<ReaderBlock> = filter { block ->
    if (block.range.start == block.range.end) {
        block.range.start in start until end || (block.range.start == end && end == start)
    } else {
        block.range.start < end && block.range.end > start
    }
}

/**
 * [block] 자체 범위와 모든 스팬 범위를 [base]만큼 이동한 값이다. 책의 블록을 절대 문서 오프셋이 아니라 자체 섹션 기준으로 저장하면 섹션을 쓸 때 한 번만 이동한다(DocumentRepositoryImpl.persistParsedDocument / importNextSections 참고). 매 페이지 나누기마다 이동하지 않으므로, 전체 재측정 때 책의 모든 블록과 스팬을 다시 배치하기 위한 같은 할당을 반복하던 일을 없앤다.
 *
 * 음수 [base]를 전달하면 반대 방향으로 이동한다. 호출자가 섹션 기준 블록을 절대값으로 다시 읽는 방식(TextPageLayoutEngine.buildPageWindow 참고)이며, 둘은 같은 연산이다.
 *
 * @receiver 이동할 블록.
 * @param base 블록과 각 스팬에서 뺄 오프셋. 반대 방향으로 이동하려면 음수를 전달하며, 섹션 기준 블록을 절대값으로 다시 읽을 때 이 방식을 사용한다.
 * @return 오프셋 하한을 0으로 제한한 이동된 복사본.
 */
fun ReaderBlock.rebasedBy(base: Long): ReaderBlock = copy(
    range = TextRange((range.start - base).coerceAtLeast(0L), (range.end - base).coerceAtLeast(0L)),
    spans = spans.map { span ->
        span.copy(
            range = TextRange(
                (span.range.start - base).coerceAtLeast(0L),
                (span.range.end - base).coerceAtLeast(0L),
            ),
        )
    },
)

/**
 * 목록의 모든 블록에 순서대로 [ReaderBlock.rebasedBy]를 적용한다.
 *
 * @receiver 이동할 블록.
 * @param base [ReaderBlock.rebasedBy]와 같이 뺄 오프셋.
 * @return 같은 순서의 이동된 복사본.
 */
fun List<ReaderBlock>.rebasedBy(base: Long): List<ReaderBlock> = map { it.rebasedBy(base) }
