package com.tedd.teddreader.core.common.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.jvm.JvmInline

/**
 * [ReaderLocation.TextOffset]의 저장 태그로, [parseReaderLocation]이 이를 다시 만들기 위해 일치시키는 접두사이다.
 */
private const val TEXT_LOCATION_PREFIX = "txt"

/**
 * [ReaderLocation.EpubOffset]의 저장 태그로, [parseReaderLocation]이 이를 다시 만들기 위해 일치시키는 접두사이다.
 */
private const val EPUB_LOCATION_PREFIX = "epub"

/**
 * [ReaderLocation.PdfPage]의 저장 태그로, [parseReaderLocation]이 이를 다시 만들기 위해 일치시키는 접두사이다.
 */
private const val PDF_LOCATION_PREFIX = "pdf"

/**
 * 1분의 밀리초 수로, [ReadingStats.wordsPerMinute]이 밀리초 기간을 분당 속도로 변환할 때 사용하는 나눗수이다.
 */
private const val MILLIS_PER_MINUTE = 60_000f

/**
 * 리더 자체 사용자 지정 테마 이외의 모든 테마가 공유하는, ARGB로 표현한 리더 내장 페이지 색상이다.
 *
 * 저장된 [ReaderStyle]이 구체적 색상을 보관하므로 디자인 시스템이 아니라 여기에 둔다. 테마는 이 색상을 스타일에 복사하여 적용하므로([withThemeMode] 참고), 이를 영속화하는 모델 계층에서 같은 상수에 접근할 수 있어야 한다.
 */
const val ReaderLightTextArgb: Long = 0xFF1F1F1FL

/**
 * [ReaderLightTextArgb]와 짝을 이루는 라이트 테마의 페이지 배경 색상이다.
 */
const val ReaderLightBackgroundArgb: Long = 0xFFFFFBF2L

/**
 * [ReaderDarkBackgroundArgb]와 짝을 이루는 다크 테마의 잉크 색상이다.
 */
const val ReaderDarkTextArgb: Long = 0xFFECE6D6L

/**
 * [ReaderDarkTextArgb]와 짝을 이루는 다크 테마의 페이지 배경 색상이다.
 */
const val ReaderDarkBackgroundArgb: Long = 0xFF12100DL

/**
 * [ReaderSepiaBackgroundArgb]와 짝을 이루는 세피아 테마의 잉크 색상이다.
 */
const val ReaderSepiaTextArgb: Long = 0xFF3B2F24L

/**
 * [ReaderSepiaTextArgb]와 짝을 이루는 세피아 테마의 페이지 배경 색상이다.
 */
const val ReaderSepiaBackgroundArgb: Long = 0xFFF4ECD8L

/**
 * 문서 자체 형식이 답할 수 있는 용어로 나타낸 독자의 문서 내 위치이다.
 *
 * 페이지 번호는 한 화면의 한 글자 크기에서만 의미가 있으므로 저장할 수 없다. 따라서 모든 위치를 책 고유의 값으로 표현한다. 재배치 가능 텍스트의 문자 오프셋, EPUB의 스파인 항목과 오프셋, 페이지 자체가 문서 고유 단위인 PDF의 페이지 번호가 이에 해당한다. 독서 위치와 저장된 장소를 모두 이 방식으로 보관하므로 글꼴 크기 변경, 다시 가져오기, 다른 기기에서도 유지된다.
 *
 * 봉인된 것이 핵심이다. 형식을 추가하면 여기에 경우를 추가해야 하며, 위치를 해석하는 모든 `when`은 해당 답을 제공할 때까지 컴파일되지 않는다.
 *
 * [asStorageString]은 디스크 형식이다. 의도적으로 직렬화용 JSON 대신 짧은 접두사 문자열을 사용하여 저장된 위치를 데이터베이스에서 `grep`으로 찾을 수 있고 저렴하게 비교할 수 있게 하며, [parseReaderLocation]은 정확한 역변환이다.
 */
@Serializable
sealed interface ReaderLocation {
    /**
     * 저장소에 기록하고 [parseReaderLocation]으로 다시 읽는 간결한 `prefix:…` 형식이다.
     */
    fun asStorageString(): String

    /**
     * 전체 결합된 텍스트의 문자 오프셋으로, 일반 텍스트 문서와 일반적인 재배치 가능 텍스트가 장소를 나타내는 방식이다.
     *
     * @property offset 전체 결합된 텍스트의 문자 위치.
     * @throws IllegalArgumentException [offset]이 음수인 경우. 손상된 저장 행을 나타낸다.
     */
    @Serializable
    @SerialName(TEXT_LOCATION_PREFIX)
    data class TextOffset(val offset: Long) : ReaderLocation {
        init {
            require(offset >= 0L) { "Text offset must be positive." }
        }

        override fun asStorageString(): String = "$TEXT_LOCATION_PREFIX:$offset"
    }

    /**
     * 스파인 항목과 그 내부의 문자 오프셋이다. 오프셋과 함께 스파인 인덱스를 보관하므로 책을 가져오는 중에도 EPUB 위치가 의미를 유지한다. 뒤 장의 오프셋은 아직 알 수 없어도 독자가 있는 장은 이미 알 수 있다.
     *
     * @property spineIndex 책 매니페스트를 읽는 순간부터 알 수 있는 스파인 항목.
     * @property offset 문서 전체 안의 문자 위치.
     * @throws IllegalArgumentException 두 값 중 하나가 음수인 경우.
     */
    @Serializable
    @SerialName(EPUB_LOCATION_PREFIX)
    data class EpubOffset(
        val spineIndex: Int,
        val offset: Long,
    ) : ReaderLocation {
        init {
            require(spineIndex >= 0) { "EPUB spine index must be positive." }
            require(offset >= 0L) { "EPUB offset must be positive." }
        }

        override fun asStorageString(): String = "$EPUB_LOCATION_PREFIX:$spineIndex:$offset"
    }

    /**
     * 파일 자체에서 페이지가 고정되어 절대 재배치되지 않는 형식의 페이지 번호이다.
     *
     * @property pageIndex 파일 자체에서 0부터 시작하는 페이지.
     * @throws IllegalArgumentException [pageIndex]가 음수인 경우.
     */
    @Serializable
    @SerialName(PDF_LOCATION_PREFIX)
    data class PdfPage(val pageIndex: Int) : ReaderLocation {
        init {
            require(pageIndex >= 0) { "PDF page index must be positive." }
        }

        override fun asStorageString(): String = "$PDF_LOCATION_PREFIX:$pageIndex"
    }
}

/**
 * [ReaderLocation.asStorageString]으로 기록한 위치를 다시 읽는다.
 *
 * 잘못된 값을 첫 페이지로 해석하지 않고 예외를 던진다. 파싱할 수 없는 저장 위치는 이 빌드가 이해하지 못하는 무언가가 행을 기록했다는 뜻이며, 독자를 조용히 책 처음으로 보내면 위치를 잃으면서 문제를 숨기게 된다.
 *
 * @param value [ReaderLocation.asStorageString]이 생성한 문자열.
 * @return 문자열이 나타내는 위치.
 * @throws IllegalStateException 접두사를 알 수 없거나 숫자가 없거나 파싱할 수 없는 경우. 이 빌드가 이해하지 못하는 무언가가 행을 기록했다는 뜻이다.
 */
fun parseReaderLocation(value: String): ReaderLocation {
    val parts = value.split(":")
    return when (parts.firstOrNull()) {
        TEXT_LOCATION_PREFIX -> ReaderLocation.TextOffset(parts.requireLong(1, value))
        EPUB_LOCATION_PREFIX -> ReaderLocation.EpubOffset(
            spineIndex = parts.requireInt(1, value),
            offset = parts.requireLong(2, value),
        )
        PDF_LOCATION_PREFIX -> ReaderLocation.PdfPage(parts.requireInt(1, value))
        else -> error("Unsupported ReaderLocation: $value")
    }
}

/**
 * 콜론으로 구분된 저장 [ReaderLocation]의 [index]번째 필드를 [Int]로 읽는다.
 *
 * @receiver [parseReaderLocation]이 이미 `:`으로 나눈 저장 값의 필드.
 * @param index 읽을 필드.
 * @param source 원본 저장 문자열로, 손상된 행을 추적할 수 있도록 오류에 포함한다.
 * @return [Int]로 파싱한 필드.
 * @throws IllegalStateException 필드가 없거나 유효한 정수가 아닌 경우.
 */
private fun List<String>.requireInt(index: Int, source: String): Int =
    getOrNull(index)?.toIntOrNull() ?: error("Invalid ReaderLocation: $source")

/**
 * 콜론으로 구분된 저장 [ReaderLocation]의 [index]번째 필드를 [Long]으로 읽는다.
 *
 * @receiver [parseReaderLocation]이 이미 `:`으로 나눈 저장 값의 필드.
 * @param index 읽을 필드.
 * @param source 원본 저장 문자열로, 손상된 행을 추적할 수 있도록 오류에 포함한다.
 * @return [Long]으로 파싱한 필드.
 * @throws IllegalStateException 필드가 없거나 유효한 정수가 아닌 경우.
 */
private fun List<String>.requireLong(index: Int, source: String): Long =
    getOrNull(index)?.toLongOrNull() ?: error("Invalid ReaderLocation: $source")

/**
 * 페이지 표시기와 진행률 막대가 표시하는, 현재까지 알려진 전체 페이지 중 독자가 있는 페이지이다.
 *
 * [total]은 "책 전체"가 아니라 "현재까지 알려진 수"이다. 가져오기나 측정이 계속되는 동안 커지며 리더는 나중에 수정할 추정값 대신 각 단계의 사실을 보여준다. [progress]를 여기서 계산하므로 모든 화면이 같은 쌍으로 같은 비율을 얻고, 전체가 0이면 나누지 않고 0을 반환한다.
 *
 * @property current 현재 표시 중인 0부터 시작하는 페이지.
 * @property total 현재까지 알려진 페이지 수로, 가져오기나 측정이 계속되는 동안 커진다.
 * @throws IllegalArgumentException 두 값 중 하나가 음수이거나 [current]가 0이 아닌 [total]을 초과하는 경우.
 */
@Serializable
data class PageIndex(
    val current: Int,
    val total: Int,
) {
    init {
        require(current >= 0) { "current page must be positive." }
        require(total >= 0) { "total page count must be positive." }
        require(current <= total || total == 0) { "current page must less than total." }
    }

    /**
     * 모든 화면이 일치하도록 여기서 계산한 [total] 대비 [current]의 비율이다. [total]이 0이면 0f이다.
     */
    val progress: Float = if (total == 0) 0f else current.toFloat() / total.toFloat()
}

/**
 * 리더 페이지를 그릴 ARGB 색상으로, 생성할 때 검증하여 저장된 스타일이 색상이 아닌 값을 지닐 수 없게 한다.
 *
 * `Long`을 인라인으로 감싸 모델 계층이 UI 색상 타입에 의존하지 않게 하고, 디자인 시스템은 경계에서 변환한다. 런타임 비용은 없다.
 *
 * @property argb `0xAARRGGBB`로 나타낸 색상.
 * @throws IllegalArgumentException [argb]가 해당 범위에 들어가지 않는 경우.
 */
@Serializable
@JvmInline
value class ReaderColor(val argb: Long) {
    init {
        require(argb in MIN_ARGB..MAX_ARGB) { "ARGB color must fit 0xAARRGGBB." }
    }
}

/**
 * 모든 채널이 0인 유효한 [ReaderColor]의 하한이다.
 */
private const val MIN_ARGB = 0x00000000L

/**
 * 모든 채널이 최댓값인 유효한 [ReaderColor]의 상한이다(`0xFFFFFFFF`).
 */
private const val MAX_ARGB = 0xFFFFFFFFL

/**
 * 책의 작성 언어와 독립적으로 앱 자체 인터페이스를 표시하는 언어이다.
 *
 * [SYSTEM]은 플랫폼 자체 로캘을 따르며 [ENGLISH]나 [KOREAN]을 선택하면 이후 기기 로캘이 바뀌어도 인터페이스를 해당 언어로 고정한다.
 */
@Serializable
enum class AppLanguage {
    SYSTEM,
    ENGLISH,
    KOREAN,
}

/**
 * 독자가 사용하는 페이지 색상 집합으로, 색상 자체와 함께 기억한다.
 *
 * 색상만으로는 그 값의 *이유*를 알 수 없으므로 모드도 저장한다. [CUSTOM]은 독자가 선택하여 어떤 것도 덮어쓸 수 없다는 뜻이고, [SYSTEM]은 여전히 플랫폼을 따라 플랫폼 변경 시 교체할 수 있다는 뜻이다.
 */
@Serializable
enum class ReaderThemeMode {
    PUBLISHER,
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA,
    CUSTOM,
}

/**
 * 내장 페이지 색상만으로 부족한 [ReaderStyle]에서 리더 텍스트 뒤에 그리는 그림이다.
 *
 * 내장 테마로 전환하면 이를 제거한다([withThemeMode] 참고). 한 페이지 색상 집합 아래에 두려고 선택한 그림은 다른 색상 아래에서 텍스트를 읽기 어렵게 할 수 있기 때문이다.
 *
 * @property uri 그림 위치.
 * @property opacity 텍스트를 읽을 수 있도록 그림이 비치는 강도로, 0..1이다.
 * @throws IllegalArgumentException [uri]가 공백이거나 [opacity]가 0..1 밖인 경우.
 */
@Serializable
data class BackgroundImage(
    val uri: String,
    val opacity: Float = 1f,
) {
    init {
        require(uri.isNotBlank()) { "Background image uri must not be blank." }
        require(opacity in 0f..1f) { "Background image opacity must be 0..1." }
    }
}

/**
 * 페이지의 외형에 관한 모든 정보로, 리더가 저장하고 그릴 때 모두 사용하는 값이다.
 *
 * 활자 필드와 색상 필드는 저장을 위해 함께 있지만 동등하지 않다. 활자만 페이지 경계를 움직이므로 [layoutKey]가 존재하며, 전체 객체 대신 이를 기준으로 측정한다.
 *
 * `init`의 범위는 저장된 스타일이 보장해야 하는 값이다. 8..80sp, 1..3× 줄 높이, 300..600 글꼴 굵기이므로 모든 화면은 영속화된 스타일을 다시 검증하지 않고 렌더링할 수 있다.
 *
 * @property fontSizeSp sp 단위 활자 크기이며 바꾸면 책을 다시 측정한다.
 * @property fontFamilyName 독자가 선택한 패밀리이며, 출판사 글꼴을 먼저 존중하고 시스템 기본값으로 대체하려면 `null`이다. 이 값도 다시 측정한다.
 * @property publisherFontKey 내장 출판사 글꼴을 위한 비영속 캐시 무효화 표식이다. 이 문서에서 어떤 내장 글꼴의 불러오기가 성공하거나 실패했는지 알기 전에는 `null`이고, 이후에는 해석된 집합의 안정적인 요약 문자열이다. 저장 설정을 오염시키지 않고 해당 집합을 새로 측정하게 한다.
 * @property lineHeightMultiplier 글꼴 크기의 배수로 나타낸 줄 높이이며, 이 값도 다시 측정한다.
 * @property fontWeight 읽기 표면의 본문 텍스트를 그리는 기준 굵기로 300, 400, 500, 600 중 하나이다. 무겁거나 가벼운 굵기는 모든 글리프 진행 폭을 바꿔 크기, 줄 높이, 패밀리처럼 줄바꿈 위치를 움직이므로 이 값도 다시 측정한다. 출판사 강조(굵게 표시한 구간, 머리말)는 고정값 대신 이 값에서 파생한다. 머리말이나 책이 지정한 굵게 표시한 구간은 이 굵기에 300을 더하고, 표 머리글 셀은 200을 더하며, 굵게 표시한 문맥 내부의 명시적 `font-weight: normal`은 이 굵기 자체로 해석된다. `core/ui`의 `ReaderSemanticText.kt`에 있는 `readerEmphasisWeights`를 참고한다. 기본값 400에서는 이전의 고정 700/600/400을 정확히 재현하므로 이 설정을 기본값에서 바꾼 독자만 강조도 함께 달라진다.
 * @property textColor 잉크 색상이며 줄바꿈을 움직일 수 없다.
 * @property backgroundColor 페이지 색상이며 역시 줄바꿈을 움직일 수 없다.
 * @property backgroundImage 텍스트 뒤의 그림이며 일반 페이지이면 `null`.
 * @property themeMode 이 색상이 나온 테마로, [withThemeMode]가 교체 가능한 값을 판단할 때 사용한다.
 * @throws IllegalArgumentException [fontSizeSp]가 8..80 밖이거나, [lineHeightMultiplier]가 1..3 밖이거나, [fontWeight]가 300..600 밖인 경우.
 */
@Serializable
data class ReaderStyle(
    val fontSizeSp: Float = 18f,
    val fontFamilyName: String? = null,
    @Transient val publisherFontKey: String? = null,
    val lineHeightMultiplier: Float = ReaderDefaultLineHeightMultiplier,
    val fontWeight: Int = ReaderDefaultFontWeight,
    val textColor: ReaderColor = ReaderColor(ReaderLightTextArgb),
    val backgroundColor: ReaderColor = ReaderColor(ReaderLightBackgroundArgb),
    val backgroundImage: BackgroundImage? = null,
    val themeMode: ReaderThemeMode = ReaderThemeMode.PUBLISHER,
) {
    init {
        require(fontSizeSp in 8f..80f) { "fontSizeSp must be 8..80." }
        require(lineHeightMultiplier in 1f..3f) { "lineHeightMultiplier must be 1..3." }
        require(fontWeight in 300..600) { "fontWeight must be 300..600." }
    }
}

/**
 * 줄 높이 슬라이더의 중립점이자 리더의 기본 줄 높이이다.
 *
 * 단순한 기본값이 아니라 줄 높이 계약의 일부다. 책이 자체 줄 높이를 지정한 블록은 슬라이더가 이 위치일 때 지정값 *그대로* 그리며, 슬라이더가 움직이면 비례해 조정한다(렌더러의 문단 스타일링 참고). 책의 값에 슬라이더 원시 값을 곱하면 독자가 아무것도 만지기 전부터 스타일 있는 모든 책의 줄이 책의 요청보다 45% 넓어졌다.
 */
const val ReaderDefaultLineHeightMultiplier: Float = 1.45f

/**
 * 글꼴 굵기 설정이 제공하는 네 굵기 300, 400, 500, 600의 가운데이자 일반 시스템 또는 웹 글꼴에서 "regular"라 부르는, 리더의 기본 본문 텍스트 굵기이다. [layoutKey]는 이 특정 값을 저장 키에 추가 토큰이 필요 없는 경우로 처리하므로, 설정을 건드리지 않은 독자는 책에 이미 측정된 모든 레이아웃을 유지한다.
 */
const val ReaderDefaultFontWeight: Int = 400

/**
 * 페이지 경계를 결정하는 [ReaderStyle]의 일부이다.
 *
 * 책 배치는 리더에서 가장 비싼 작업이며 활자만 결과를 결정한다. 같은 텍스트를 같은 크기, 줄 높이, 패밀리로 표시하면 색상과 관계없이 같은 위치에서 페이지가 나뉜다. 전체 스타일을 비교하면 테마 전환이 새 측정처럼 보여 단 한 줄도 움직일 수 없는 변경 때문에 책 전체를 다시 배치했다.
 *
 * @property fontSizeSp 페이지를 측정한 활자 크기.
 * @property lineHeightMultiplier 페이지를 측정한 줄 높이.
 * @property fontFamilyName 측정에 사용한 패밀리이며 시스템 기본값이면 `null`.
 */
data class ReaderLayoutKey(
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val fontFamilyName: String?,
)

/**
 * 이 스타일의 [ReaderLayoutKey]로, "다시 측정해야 하는가?"라는 질문에 답할 때 비교하는 값이다. [ReaderStyle.fontWeight]는 크기, 줄 높이, 패밀리와 마찬가지로 이 키에 속한다. 무겁거나 가벼운 굵기가 모든 글리프 진행 폭을 바꿔 줄바꿈을 움직이기 때문이다. 하지만 [ReaderLayoutKey] 자체에는 네 번째 열을 추가하지 않는다. Room 계층은 저장된 레이아웃을 정확히 [ReaderLayoutKey.fontSizeSp], [ReaderLayoutKey.lineHeightMultiplier], [ReaderLayoutKey.fontFamilyName]을 키로 사용한다(`DocumentRepositoryImpl.newestStoredViewportSize` 참고). 네 번째 필드는 스키마 마이그레이션을 뜻한다. 대신 [fontWeightToken]이 굵기를 패밀리 문자열에 합친다. [ReaderStyle.publisherFontKey]와 같은 방식이며, [ReaderDefaultFontWeight]에서는 빈 문자열을 반환한다. 따라서 이 설정 출시 순간 모든 책의 기존 레이아웃이 오래된 상태가 되지 않고, 설정을 건드리지 않은 독자는 이미 측정한 모든 레이아웃을 유지한다.
 *
 * @receiver 축약할 스타일.
 * @return 페이지 경계를 결정하는 필드만 포함한 값.
 */
fun ReaderStyle.layoutKey(): ReaderLayoutKey = ReaderLayoutKey(
    fontSizeSp = fontSizeSp,
    lineHeightMultiplier = lineHeightMultiplier,
    fontFamilyName = "${fontFamilyName ?: publisherFontKey ?: ""}${fontWeightToken()}$LayoutAlgorithmVersionSuffix",
)

/**
 * [ReaderLayoutKey] 자체에 굵기 열이 없으므로 [layoutKey]가 [ReaderStyle.fontWeight]를 패밀리 문자열에 합칠 때 사용하는 토큰이다. 이유는 [layoutKey] 자체 문서를 참고한다.
 *
 * @receiver 굵기를 읽을 스타일.
 * @return [ReaderDefaultFontWeight]에서는 빈 문자열로, 건드리지 않은 설정은 저장 키를 전혀 바꾸지 않는다. 그 외에는 `"|w<weight>"`이며, 굵기가 실제로 기본값과 달라지면 새 측정과 별도 저장 레이아웃을 강제한다.
 */
private fun ReaderStyle.fontWeightToken(): String =
    if (fontWeight == ReaderDefaultFontWeight) "" else "|w$fontWeight"

/**
 * 실시간 스타일의 다른 모든 선택, 즉 색상, 배경 이미지, 테마는 유지하면서 레이아웃에 영향을 주는 필드를 [measured]의 값으로 교체한 스타일이다. 실제로 페이지를 나눌 때 사용한 활자로 페이지 집합을 그리는 데 사용한다.
 *
 * 레이아웃에 영향을 주는 설정(글꼴 패밀리, 글꼴 크기, 줄 높이, 글꼴 굵기)을 바꾸면 렌더링 경로에는 새 [ReaderStyle]을 즉시 게시하지만 화면의 페이지 조각은 *이전* 스타일로 잘라져 있다. 창이 재구성·재측정·보고한 뒤에야 다시 측정되며, 설정 변경이 책을 비우지 않고 기다릴 수 없는 비동기 왕복 과정이다. 그 사이 새 스타일로 기존 조각을 그리면 한 글꼴/크기/줄 높이로 측정한 페이지에 다른 글리프 레이아웃을 섞어 마지막 줄이 잘리거나 아래에 빈틈이 생긴다. 이 함수는 [layoutKey]의 정확한 역변환이다. 페이지 경계를 결정하는 필드만 복사하여 호출자가 "실시간 스타일이되 화면 조각 자체 활자를 사용한 값"을 만들 수 있게 한다. 이 함수의 유일한 사용 위치인 `ReaderUiState.pageDrawStyle`을 참고한다.
 *
 * 여기서 복사하는 필드는 [layoutKey] 자체 필드와 반드시 함께 변경해야 한다. 이제 코드베이스에서 두 번째로 "레이아웃에 포함되는 것"에 합의해야 하는 위치이기 때문이다. 한쪽에만 레이아웃 필드를 추가하면 이 함수가 막으려는 오래된 조각 결함이 조용히 다시 열린다.
 *
 * @receiver 실시간 스타일로, 색상과 테마 필드는 결과에서도 바뀌지 않는다.
 * @param measured 화면 페이지 조각을 실제로 측정하고 배치할 때 사용한 스타일.
 * @return [ReaderStyle.fontSizeSp], [ReaderStyle.fontFamilyName], [ReaderStyle.publisherFontKey], [ReaderStyle.lineHeightMultiplier], [ReaderStyle.fontWeight]는 [measured]에서 가져오고, 색상, 배경 이미지, 테마 모드 등 나머지는 수신 객체에서 가져온 스타일.
 */
fun ReaderStyle.withLayoutFieldsOf(measured: ReaderStyle): ReaderStyle = copy(
    fontSizeSp = measured.fontSizeSp,
    lineHeightMultiplier = measured.lineHeightMultiplier,
    fontFamilyName = measured.fontFamilyName,
    fontWeight = measured.fontWeight,
    publisherFontKey = measured.publisherFontKey,
)

/**
 * *레이아웃 알고리즘*이 바뀔 때마다 올려 모든 [ReaderLayoutKey]의 패밀리 필드에 합치는 표식이다. 간격 크기 계산, 스타일 해석 등 문자를 움직이지 않고 줄을 움직이는 모든 변경이 해당한다.
 *
 * 저장된 페이지 레이아웃은 레이아웃 키와 문자 수를 키로 삼으므로, 텍스트가 그대로인 알고리즘 변경은 그렇지 않으면 이전 코드로 측정한 페이지 경계를 계속 제공하여 사용자가 우연히 설정을 바꿀 때까지 페이지를 자른다. 키에 버전을 합치면 이전 알고리즘의 모든 저장 레이아웃이 명확한 캐시 미스가 되고 저장소 자체 정리가 이를 제거한다.
 */
private const val LayoutAlgorithmVersionSuffix = "#layout8"

/**
 * 테마 선택을 적용하는 방식으로 [mode]를 적용한 스타일이다. 모드의 색상을 복사하고 해당 모드도 함께 기록한다.
 *
 * 내장 테마로 전환하면 배경 이미지도 제거한다. 한 페이지 색상 집합을 위해 고른 그림은 다른 색상에서 텍스트를 읽기 어렵게 할 수 있기 때문이다. [ReaderThemeMode.CUSTOM]은 모든 색상을 그대로 둔다. "독자가 이 색상을 선택했다"는 뜻이므로 덮어쓸 것이 없다.
 *
 * @receiver 변환할 스타일.
 * @param mode 적용할 테마.
 * @return [mode]를 적용한 스타일. 내장 테마는 색상을 교체하고 배경 이미지를 제거하며, `CUSTOM`은 모든 색상을 유지하고 모드만 기록한다.
 */
fun ReaderStyle.withThemeMode(mode: ReaderThemeMode): ReaderStyle = when (mode) {
    ReaderThemeMode.PUBLISHER,
    ReaderThemeMode.LIGHT,
        -> copy(
            textColor = ReaderColor(ReaderLightTextArgb),
            backgroundColor = ReaderColor(ReaderLightBackgroundArgb),
            backgroundImage = null,
            themeMode = mode,
        )

    ReaderThemeMode.SYSTEM -> copy(
        textColor = ReaderColor(ReaderLightTextArgb),
        backgroundColor = ReaderColor(ReaderLightBackgroundArgb),
        backgroundImage = null,
        themeMode = ReaderThemeMode.SYSTEM,
    )

    ReaderThemeMode.DARK -> copy(
        textColor = ReaderColor(ReaderDarkTextArgb),
        backgroundColor = ReaderColor(ReaderDarkBackgroundArgb),
        backgroundImage = null,
        themeMode = ReaderThemeMode.DARK,
    )

    ReaderThemeMode.SEPIA -> copy(
        textColor = ReaderColor(ReaderSepiaTextArgb),
        backgroundColor = ReaderColor(ReaderSepiaBackgroundArgb),
        backgroundImage = null,
        themeMode = ReaderThemeMode.SEPIA,
    )

    ReaderThemeMode.CUSTOM -> copy(
        themeMode = ReaderThemeMode.CUSTOM,
    )
}

/**
 * 대체값 색상이 플랫폼의 현재 다크 테마 설정을 따르는 스타일에 해당 설정을 적용한다.
 *
 * [ReaderThemeMode.SYSTEM]과 [ReaderThemeMode.PUBLISHER]는 모두 실시간 시스템 설정으로 앱 UI 외곽을 구동한다. 해당 설정은 나중에 바뀔 수 있으므로 저장 대체값은 라이트 모드로 유지한다. 다크 모드 기기에서는 이 함수가 둘 다 어두운 종이와 밝은 잉크로 해석하여 ReaderScreen이 어두운 UI 외곽과 검은색 텍스트 대체값을 결합하지 않게 한다. 출판사가 명시적으로 제공한 EPUB 전경/배경 색상은 모드가 [ReaderThemeMode.PUBLISHER]로 유지되므로 렌더러에서 여전히 이 대체값을 재정의한다.
 *
 * 명시적 라이트, 다크, 세피아, 사용자 지정 모드는 그대로 반환한다. 페이지 레이아웃에는 영향이 없다. [layoutKey]에는 색상 필드가 없다.
 *
 * @receiver 영속화된 스타일로, [ReaderStyle.themeMode]가 변경 여부를 결정한다.
 * @param systemInDarkTheme UI 계층에서 읽은 플랫폼의 실시간 다크 테마 플래그.
 * @return [ReaderStyle.themeMode]가 다크 시스템을 따를 때 다크 대체 색상을 적용한 스타일이며, 그 외에는 변경하지 않는다.
 */
fun ReaderStyle.resolveSystemTheme(systemInDarkTheme: Boolean): ReaderStyle =
    if (
        systemInDarkTheme &&
        (themeMode == ReaderThemeMode.SYSTEM || themeMode == ReaderThemeMode.PUBLISHER)
    ) {
        copy(
            textColor = ReaderColor(ReaderDarkTextArgb),
            backgroundColor = ReaderColor(ReaderDarkBackgroundArgb),
        )
    } else {
        this
    }

/**
 * 변환할 기존 스타일이 없는 호출자를 위한 전체 다크 테마 스타일이다.
 *
 * @return 기본 활자를 사용하는 완전한 다크 테마 스타일.
 */
fun darkReaderStyle(): ReaderStyle = ReaderStyle(
    textColor = ReaderColor(ReaderDarkTextArgb),
    backgroundColor = ReaderColor(ReaderDarkBackgroundArgb),
    themeMode = ReaderThemeMode.DARK,
)

/**
 * 변환할 기존 스타일이 없는 호출자를 위한 전체 세피아 테마 스타일이다.
 *
 * @return 기본 활자를 사용하는 완전한 세피아 테마 스타일.
 */
fun sepiaReaderStyle(): ReaderStyle = ReaderStyle(
    textColor = ReaderColor(ReaderSepiaTextArgb),
    backgroundColor = ReaderColor(ReaderSepiaBackgroundArgb),
    themeMode = ReaderThemeMode.SEPIA,
)

/**
 * 페이지 전환 방향으로, 스와이프나 가장자리 탭을 해석하는 방식도 결정한다.
 *
 * [CONTINUOUS]는 읽기만 하고 쓰지 않는다. 이전 설치가 저장한 값이므로 설정을 계속 역직렬화할 수 있게 열거형에 남겨 두며, 해석하는 모든 곳에서 [VERTICAL]로 처리한다.
 */
@Serializable
enum class PageTurnMode {
    HORIZONTAL,
    VERTICAL,
    CONTINUOUS,
}

/**
 * 페이지 전환을 애니메이션하는 방식이다. 리더는 이 값으로 페이저 구현을 선택하므로, 이 집합은 시각 효과 목록이 아니라 실제 존재하는 페이저 목록이다.
 *
 * [BOOK_CURL]과 [SHEET_FLIP]은 교체된 페이저의 읽기 전용 잔여 값이다. 이전 저장 설정을 계속 역직렬화할 수 있도록 남겨 두고 각각 [CURL_PAGER]와 [SLIDE]로 해석한다.
 */
@Serializable
enum class PageAnimation {
    NONE,
    SLIDE,
    FADE,
    SCROLL,
    BOOK_CURL,
    SHEET_FLIP,
    FLUID_PAGER,
    CURL_PAGER,
    THREE_D_CURL,
    CIRCLE_REVEAL,
    MOVIE_CAROUSEL,
    PAGE_FLIP,
}

/**
 * 자동 스크롤이 이동하는 단위로, 부드러운 이동의 픽셀, 일정한 읽기를 위한 전체 줄, 전체 페이지 중 하나이다. 단위에 따라 "속도"의 의미가 달라지므로 [AutoScrollConfig]에 둘을 함께 저장한다.
 */
@Serializable
enum class AutoScrollMode {
    PIXEL,
    LINE,
    PAGE,
}

/**
 * 활성화 여부, 이동 단위, 속도를 하나로 묶은 자동 스크롤 설정이다.
 *
 * 실제 의미가 [mode]와 기기 밀도에 따라 달라지므로 속도를 초당 픽셀 또는 줄 수가 아닌 `MIN_SPEED..MAX_SPEED`로 정규화하여 저장한다. 각 페이저가 사용하는 지점에서 변환한다. `init` 경계는 보정하지 않고 거부하므로 슬라이더가 생성 전에 제한할 수 있도록 [clampSpeed]를 공개한다.
 *
 * @property enabled 자동 스크롤이 실행 중인지 여부.
 * @property mode 이동 단위로, [speed]의 의미를 결정한다.
 * @property speed 0.01..1로 정규화한 값이며 각 페이저가 사용하는 지점에서 픽셀 또는 줄로 변환한다.
 * @throws IllegalArgumentException [speed]가 양수가 아닌 경우. `init`은 보정하지 않고 거부하므로 슬라이더 값에는 먼저 [AutoScrollConfig.clampSpeed]를 사용한다.
 */
@Serializable
data class AutoScrollConfig(
    val enabled: Boolean = false,
    val mode: AutoScrollMode = AutoScrollMode.PIXEL,
    val speed: Float = MAX_SPEED,
) {
    init {
        require(speed > 0f) { "Auto-scroll speed must be positive." }
    }

    /**
     * [AutoScrollConfig.speed]의 경계와 제한 도우미이다.
     */
    companion object {
        /**
         * [speed]가 허용하는 가장 느린 속도이다. `init` 블록은 0 이하 값을 거부한다.
         */
        const val MIN_SPEED: Float = 0.01f

        /**
         * [speed]가 허용하는 가장 빠른 속도이자, 값을 제공하지 않을 때 설정이 사용하는 기본값이다.
         */
        const val MAX_SPEED: Float = 1f

        /**
         * 슬라이더에서 직접 받은 값과 같은 원시 값을 [AutoScrollConfig]가 허용하는 범위로 제한한다. 범위 밖 속도 때문에 `init` 블록의 [IllegalArgumentException]이 발생해서는 안 되는 호출자가 사용한다.
         *
         * @param speed 제한할 원시 속도 값.
         * @return [MIN_SPEED]..[MAX_SPEED]로 제한한 [speed].
         */
        fun clampSpeed(speed: Float): Float = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }
}

/**
 * 페이지를 배치할 영역의 크기이다. 같은 책과 활자도 다른 상자에서는 다르게 나뉘므로 저장 페이지 레이아웃의 키 일부이다.
 *
 * 단위는 호출자가 사용하는 것을 따르며 여기에 인코딩하지 않는다. 리더는 페이지 나누기 뷰포트에 sp를, 창 측정 뷰포트에 px를 사용하므로 그 경계 양쪽의 호출자는 둘을 섞으면 안 된다.
 *
 * @property widthPx 페이지를 배치할 상자의 너비.
 * @property heightPx 해당 상자의 높이.
 * @throws IllegalArgumentException 둘 중 하나가 양수가 아닌 경우. 빈 공간에는 페이지를 배치할 수 없다.
 */
@Serializable
data class ViewportSize(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(widthPx > 0) { "Viewport width must be positive." }
        require(heightPx > 0) { "Viewport height must be positive." }
    }
}

/**
 * 리더가 그리는 페이지 하나로, 번호·문서 내 시작 위치·텍스트·텍스트를 스타일링하는 블록 구조를 담는다.
 *
 * [location]은 페이지를 다시 나눈 뒤에도 페이지를 찾을 수 있게 한다. 리더는 이를 저장하고 페이지 모양이 바뀌면 이 값으로 다시 찾는다. [textRange]는 절대 문서 오프셋으로 나타낸 같은 범위이며 검색 결과, 책갈피, 섹션 조회가 이를 비교한다.
 *
 * [blocks]가 비어 있는 것은 버그가 아니라 상태이다. 블록 구조를 아직 디코딩하지 않은 섹션은 일반 텍스트 페이지를 만들고, 디코딩한 뒤 같은 페이지를 다시 게시한다. 스타일 적용 텍스트가 필요한 호출자는 페이지를 만든 뒤가 아니라 만들기 전에 블록이 디코딩됐는지 확인해야 한다.
 *
 * @property pageIndex 이 페이지의 번호와 생성 시점에 알려진 전체 페이지 수.
 * @property location 페이지를 다시 나눈 뒤 다시 찾을 때 사용하는 페이지 시작 위치.
 * @property text 바로 그릴 수 있는 페이지 텍스트.
 * @property textRange 검색, 책갈피, 섹션 조회에 사용하는 절대 문서 오프셋의 같은 범위.
 * @property blocks [text]를 스타일링하는 구조. 비어 있으면 섹션 블록을 아직 디코딩하지 않아 페이지를 일반 텍스트로 렌더링하며, 디코딩한 뒤 다시 게시한다.
 */
@Serializable
data class PageWindow(
    val pageIndex: PageIndex,
    val location: ReaderLocation,
    val text: String,
    val textRange: TextRange? = null,
    val blocks: List<ReaderBlock> = emptyList(),
)

/**
 * 문서 정보 화면에 표시할 문서 하나의 독서 합계이다.
 *
 * [wordsPerMinute]은 저장하지 않고 파생하므로 원본 수치와 다를 수 없다. 측정된 독서 시간이 없으면 0을 반환하며, 현재는 독서 세션을 아무것도 기록하지 않으므로 항상 해당한다(ReadingStatsRepository 참고).
 *
 * @property documentId 요약 대상 문서.
 * @property activeMillis 합산한 독서 시간으로, 현재는 어떤 세션도 기록하지 않아 0이다.
 * @property charactersRead 문서 자체에서 얻은 책의 문자 수.
 * @property wordsRead 문서 자체에서 얻은 책의 단어 수.
 * @throws IllegalArgumentException 수치 중 하나가 음수인 경우.
 */
@Serializable
data class ReadingStats(
    val documentId: DocumentId,
    val activeMillis: Long,
    val charactersRead: Long,
    val wordsRead: Long,
) {
    init {
        require(activeMillis >= 0L) { "activeMillis must be positive." }
        require(charactersRead >= 0L) { "charactersRead must be positive." }
        require(wordsRead >= 0L) { "wordsRead must be positive." }
    }

    /**
     * [activeMillis] 1분당 [wordsRead]로, 두 수치와 다를 수 없도록 파생한다. 기록된 독서 시간이 없으면 0f이다.
     */
    val wordsPerMinute: Float = if (activeMillis == 0L) {
        0f
    } else {
        wordsRead.toFloat() / (activeMillis.toFloat() / MILLIS_PER_MINUTE)
    }
}
