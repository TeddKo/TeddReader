package com.tedd.teddreader.core.common.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * 문서를 가져온 URI로 나타내는 문서의 정체성이다.
 *
 * 생성한 키 대신 원본 URI를 사용하므로 앱이 이미 보유한 책을 다시 열 때 알아볼 수 있다. 같은 파일을 앱에 두 번 전달하면 같은 ID로 해석되어 두 번째 열기에서는 다시 가져오지 않고 저장된 텍스트와 페이지 레이아웃을 재사용한다. 또한 진행률, 책갈피, 검색 인덱스, 페이지 레이아웃 등 모든 파생 행이 호출자가 이미 가진 값을 키로 사용한다.
 *
 * `String`을 인라인으로 감싸 ID 전달 비용은 없으면서 일반 문자열을 ID로 오인할 수 없게 한다. 여기서 공백 값을 거부하므로 하위 계층은 확인할 필요가 없다.
 *
 * @property value 문서를 가져온 원본 URI이며 동시에 문서의 정체성이다.
 * @throws IllegalArgumentException [value]가 공백인 경우. 공백 ID는 다른 모든 공백 ID와 충돌하고 다시 찾을 수 없는 행의 키가 된다.
 */
@Serializable
@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId must not be blank." }
    }

    /**
     * 문서 ID를 문자열 래퍼가 아니라 자체 문자열처럼 로그와 출력에 표시한다.
     */
    override fun toString(): String = value
}

/**
 * 파일 이름과 MIME 타입으로 가져올 때 한 번 해석하여 저장하는 문서 종류이다.
 *
 * 리더의 거의 모든 동작 분기는 여기서 시작한다. 텍스트 검색 가능 여부, 페이지 재배치 여부, 페이지가 이미지인지 여부를 각 위치에서 열거형 값 비교로 다시 계산하지 않고 질문 자체를 [isVisualPageFormat]과 [isImagePageFormat]으로 명명한다.
 *
 * [UNKNOWN]은 실패가 아니라 실제 상태이다. 앱에 전달됐지만 분류할 수 없는 파일도 목록에 표시하며, 읽을 재배치 가능한 내용이 없는 것으로 처리할 뿐이다.
 */
@Serializable
enum class DocumentFormat {
    TXT,
    PDF,
    EPUB,
    CBZ,
    IMAGE,
    UNKNOWN,
}

/**
 * 페이지가 재배치 가능 텍스트가 아니라 이미지로 제공되는지 나타낸다.
 *
 * `true`이면 배치할 텍스트가 없어 페이지 나누기, 검색, 텍스트 스타일을 적용하지 않으며 페이지를 넘길 때마다 페이지 이미지를 가져온다. 의도적으로 모든 경우를 다루는 `when`으로 작성했다. 새 형식은 이 질문에 답해야 컴파일된다.
 *
 * @receiver 검사할 형식.
 * @return PDF, CBZ, 단일 이미지면 `true`이고 텍스트 형식과 분류되지 않은 파일이면 `false`.
 */
fun DocumentFormat.isVisualPageFormat(): Boolean = when (this) {
    DocumentFormat.PDF,
    DocumentFormat.CBZ,
    DocumentFormat.IMAGE,
        -> true
    DocumentFormat.TXT,
    DocumentFormat.EPUB,
    DocumentFormat.UNKNOWN,
        -> false
}

/**
 * PDF처럼 페이지가 렌더링되는 시각 형식과 달리, 페이지를 가득 채우는 단일 그림인지, 즉 만화 페이지 또는 독립 이미지인지 나타낸다.
 *
 * @receiver 검사할 형식.
 * @return CBZ와 단일 이미지면 `true`이고 나머지는 모두 `false`이다. 페이지를 저장된 그림이 아니라 렌더링하는 PDF도 `false`이다.
 */
fun DocumentFormat.isImagePageFormat(): Boolean =
    this == DocumentFormat.CBZ || this == DocumentFormat.IMAGE

/**
 * 문서의 출처와 이름으로, URI, 표시할 이름, 플랫폼이 제공한 타입과 크기를 담는다.
 *
 * 선택기가 항상 타입을 제공하지 않으므로 [mimeType]은 `null` 허용이다. 따라서 형식 감지는 이름도 읽으며 MIME 타입에만 의존하지 않는다. [displayName]은 서재에 표시하고 확장자 기반 감지에서 읽는 값이므로 URI에서 파생하지 않고 필수로 받는다.
 *
 * @property sourceUri 문서 위치이며 [DocumentId]를 만드는 값.
 * @property displayName 표시할 이름이며 확장자 기반 형식 감지가 읽는 값.
 * @property mimeType 플랫폼이 제공한 파일 타입이며 선택기가 아무것도 제공하지 않으면 `null`.
 * @property sizeBytes 보고된 파일 크기이며 알 수 없으면 0.
 * @throws IllegalArgumentException [sourceUri]나 [displayName]이 공백이거나 [sizeBytes]가 음수인 경우.
 */
@Serializable
data class DocumentLocation(
    val sourceUri: String,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
) {
    init {
        require(sourceUri.isNotBlank()) { "sourceUri must not be blank." }
        require(displayName.isNotBlank()) { "displayName must not be blank." }
        require(sizeBytes >= 0L) { "sizeBytes must be positive." }
    }
}

/**
 * 문서를 열지 않고 서재가 아는 정보로, 정체성·출처·형식과 목록에 필요한 개수 및 플래그를 담는다.
 *
 * 책장 목록을 표시하면서 어떤 책도 불러오지 않아야 하므로 문서 텍스트와 의도적으로 분리했다. 홈 화면은 이 행만으로 렌더링한다.
 *
 * `null` 허용 개수는 0이 아니라 "아직 알 수 없음"을 뜻하며, 점진적 가져오기가 끝나기 전 책의 상태가 이에 해당한다. 서재는 `null`인 [characterCount]를 완료되지 않은 가져오기로 해석한다. [folderId]와 [folderName]은 둘 다 있거나 둘 다 없도록 제한하여, 행이 이름 없는 폴더에 속한다고 표시할 수 없게 한다.
 *
 * @property id 문서의 정체성.
 * @property location 문서의 출처와 표시할 이름.
 * @property format 가져올 때 한 번 해석한 문서 종류.
 * @property addedAtEpochMillis 문서를 가져온 시각으로, 처음 열기 전 서재 정렬에 사용한다.
 * @property lastOpenedAtEpochMillis 마지막으로 연 시각이며 한 번도 열지 않았으면 `null`.
 * @property pageCount 마지막으로 측정한 페이지 수이며 아직 측정하지 않았으면 `null`.
 * @property characterCount 텍스트 문자 수이며 가져오기가 끝나지 않았으면 `null`이다. 서재는 이 값으로 아직 파싱 중인 책을 알아본다.
 * @property wordCount 텍스트 단어 수이며 [characterCount]와 같은 이유로 `null`일 수 있다.
 * @property isBookmarked 독자가 서재에서 이 책을 별표 표시했는지 여부.
 * @property folderId 이 책을 분류한 폴더이며 분류하지 않았으면 `null`.
 * @property folderName 해당 폴더 이름으로, [folderId]가 있을 때만 존재한다.
 * @throws IllegalArgumentException 타임스탬프나 개수가 음수이거나, [folderId]와 [folderName] 중 하나만 있거나, 둘 중 하나가 공백인 경우.
 */
@Serializable
data class DocumentMetadata(
    val id: DocumentId,
    val location: DocumentLocation,
    val format: DocumentFormat,
    val addedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long? = null,
    val pageCount: Int? = null,
    val characterCount: Long? = null,
    val wordCount: Long? = null,
    val isBookmarked: Boolean = false,
    val folderId: String? = null,
    val folderName: String? = null,
) {
    init {
        require(addedAtEpochMillis >= 0L) { "addedAtEpochMillis must be positive." }
        require(lastOpenedAtEpochMillis == null || lastOpenedAtEpochMillis >= 0L) {
            "lastOpenedAtEpochMillis must be positive."
        }
        require(pageCount == null || pageCount >= 0) { "pageCount must be positive." }
        require(characterCount == null || characterCount >= 0L) { "characterCount must be positive." }
        require(wordCount == null || wordCount >= 0L) { "wordCount must be positive." }
        require((folderId == null) == (folderName == null)) {
            "folderId and folderName must both be null or both be non-null."
        }
        require(folderId == null || folderId.isNotBlank()) { "folderId must not be blank." }
        require(folderName == null || folderName.isNotBlank()) { "folderName must not be blank." }
    }
}

/**
 * 서재와 같은 방식으로 문자 수의 존재 여부를 통해 이 문서의 가져오기가 끝났는지 판단한다.
 *
 * 개수는 문서 파싱이 완전히 끝났을 때만 기록하므로, 값이 없다는 사실은 가져오기가 여전히 실행 중이라는 서재 자체 신호이다. 부분적으로 가져온 책이 페이지 수도 표시하지 않는 이유다. 각 호출 위치에서 다시 계산하지 않고 이름을 붙였다. 호출자가 도메인 질문을 하려는 곳에서 "characterCount != `null`"은 저장소 사실만 드러내며, 여러 위치의 복사본은 서로 달라질 수 있기 때문이다.
 *
 * @receiver 판단할 서재 행.
 * @return 이 행을 만든 가져오기가 완료됐으면 `true`.
 */
val DocumentMetadata.isImportFinished: Boolean get() = characterCount != null

/**
 * 합쳐진 문서 텍스트의 절대 문자 오프셋으로 나타낸 반개구간이다.
 *
 * 페이지 범위, 검색 일치 항목, 책갈피, 섹션 경계처럼 페이지를 다시 나눈 뒤에도 같은 구절을 가리켜야 하는 모든 요소가 이 오프셋을 사용한다. 섹션 기준이 아니라 절대값이므로 서로 다른 섹션의 두 범위를 직접 비교할 수 있다.
 *
 * @property start 범위의 첫 문자에 해당하는 절대 문서 오프셋.
 * @property end 마지막 문자 다음 값으로, 빈 범위에서는 [start] == [end]이다.
 * @throws IllegalArgumentException [start]가 음수이거나 [end]가 [start]보다 앞서는 경우.
 */
@Serializable
data class TextRange(
    val start: Long,
    val end: Long,
) {
    init {
        require(start >= 0L) { "TextRange start must be positive." }
        require(end >= start) { "TextRange end must greater than start." }
    }
}

/**
 * 장, EPUB 스파인 항목, 텍스트 파일 전체 본문 등 문서 형식이 나누는 단위 하나로, 텍스트와 문서 전체에서 그 텍스트가 위치하는 곳을 담는다.
 *
 * 섹션은 비용이 큰 모든 작업의 단위이다. 파싱, 저장, 블록 구조 디코딩, 페이지 측정을 섹션별로 수행하므로 책의 나머지를 가져오는 중에도 열 수 있다. [index]는 문서 자체 순서에서의 위치이며 이후 섹션이 추가돼도 안정적으로 유지되므로 저장된 위치가 계속 같은 구절을 가리킨다.
 *
 * @property index 문서 자체 순서에서 이 섹션의 위치로, 이후 섹션이 추가돼도 유지된다.
 * @property text 줄 끝이 이미 정규화된 섹션 텍스트.
 * @property range 전체 문서에서 이 텍스트가 위치하는 절대 오프셋 범위.
 * @property title 형식에 제목이 있으면 이 섹션 자체 제목이며, 없으면 `null`.
 * @throws IllegalArgumentException [index]가 음수인 경우.
 */
@Serializable
data class ReaderSection(
    val index: Int,
    val text: String,
    val range: TextRange,
    val title: String? = null,
) {
    init {
        require(index >= 0) { "ReaderSection index must be positive." }
    }
}

/**
 * 문서 목차의 항목 하나로, 제목·깊이·대상 위치를 담는다.
 *
 * 책을 가져오는 중에도 항목을 사용할 수 있도록 대상을 [spineIndex]와 [offset]으로 모두 보관한다. 스파인 위치는 처음부터 알 수 있고 절대 오프셋은 앞 섹션을 파싱한 뒤에야 알 수 있다.
 *
 * @property title 책에 적힌 항목 제목.
 * @property level 최상위 항목을 1로 하는 중첩 깊이.
 * @property spineIndex 이 항목이 가리키는 스파인 항목으로, 가져오기 시작부터 알 수 있다.
 * @property offset 이 항목이 가리키는 절대 텍스트 오프셋으로, 앞 섹션의 파싱이 끝나면 해석된다.
 * @throws IllegalArgumentException [title]이 공백이거나 [level]이 1보다 작거나 두 위치 중 하나가 음수인 경우.
 */
@Serializable
data class ReaderNavigationItem(
    val title: String,
    val level: Int,
    val spineIndex: Int,
    val offset: Long,
) {
    init {
        require(title.isNotBlank()) { "ReaderNavigationItem title must not be blank." }
        require(level >= 1) { "ReaderNavigationItem level must be positive." }
        require(spineIndex >= 0) { "ReaderNavigationItem spineIndex must be positive." }
        require(offset >= 0L) { "ReaderNavigationItem offset must be positive." }
    }
}

/**
 * 책이 이름을 제공하면 그 제목("Contents", "목차")도 담는 문서 목차이다. 탐색을 제공하지 않는 형식에는 아예 없으므로, 리더는 빈 목록과 `null` 탐색을 같은 방식으로 처리한다. 둘 다 표시할 내용이 없다.
 *
 * @property heading 책 자체의 목차 이름이며 제공하지 않으면 `null`.
 * @property items 문서 순서의 항목이며, 비어 있으면 사용할 수 있는 탐색이 없는 책이다.
 */
@Serializable
data class ReaderNavigation(
    val heading: String? = null,
    val items: List<ReaderNavigationItem> = emptyList(),
)

/**
 * 리더가 읽는 형태의 문서로, 섹션, 블록 구조, 탐색을 담는다.
 *
 * [DocumentMetadata]와 구별되는 파싱된 형태이며, 점진적 가져오기 중인 책에서는 *지금까지* 파싱된 내용을 담는다. 리더는 가져오기 묶음이 끝날 때마다 다시 읽어 문서가 커지는 것을 확인한다.
 *
 * [characterCount]와 [wordCount]는 저장하지 않고 섹션에서 계산하므로 실제 존재하는 텍스트와 다를 수 없다. [characterCount]는 저장된 페이지 레이아웃을 확인하는 지문이기도 하다. 값이 바뀌면 레이아웃이 측정된 오프셋이 더는 이 문서를 설명하지 않으므로, 레이아웃을 신뢰하지 않고 버린다.
 *
 * @property id 문서의 정체성.
 * @property format 아래 텍스트의 적용 여부를 결정하는 문서 종류.
 * @property title 리더 자체 UI 외곽에 표시할 책 제목.
 * @property sections 지금까지 파싱된 섹션을 문서 순서로 담은 값.
 * @property pageCount 마지막으로 측정한 페이지 수이며 이 문서를 측정한 적이 없으면 `null`.
 * @property navigation 목차이며 탐색이 없는 형식이면 `null`.
 */
@Serializable
data class ReaderDocument(
    val id: DocumentId,
    val format: DocumentFormat,
    val title: String,
    val sections: List<ReaderSection>,
    val pageCount: Int? = null,
    /**
     * 형식이 구조를 제공하면 합쳐진 섹션 텍스트의 구조를 담는다. 비어 있으면 작성된 그대로 텍스트를 읽으며, 일반 텍스트 파일은 이것으로 충분하다. 범위는 페이지 나누기와 독서 위치가 사용하는 텍스트와 동일하게 섹션을 단일 줄 바꿈 문자로 합친 결과를 가리킨다.
     */
    val blocks: List<ReaderBlock> = emptyList(),
    val navigation: ReaderNavigation? = null,
) {
    init {
        require(title.isNotBlank()) { "ReaderDocument title must not be blank." }
        require(pageCount == null || pageCount >= 0) { "pageCount must be positive." }
    }

    /**
     * 파싱된 모든 섹션의 문자 수로, 캐시하지 않고 접근할 때마다 합산해 다시 계산한다.
     */
    val characterCount: Long get() = sections.sumOf { section -> section.text.length.toLong() }

    /**
     * 파싱된 모든 섹션의 단어 수로, 캐시하지 않고 접근할 때마다 합산해 다시 계산한다.
     */
    val wordCount: Long get() = sections.sumOf { section -> section.text.wordCount().toLong() }
}

/**
 * 문서에서 검색 질의가 나타난 한 위치로, 표시하고 이동하기에 충분한 문맥을 담는다.
 *
 * [location]은 탭할 때 이동할 위치이고 [range]는 문서 오프셋에서의 정확한 범위이며, 둘을 분리한다. 전자는 리더를 옮길 수 있는 위치이고 후자는 강조와 중복 제거가 비교하는 대상이기 때문이다. [query]를 결과와 함께 전달하므로 화면은 무엇을 검색했는지 따로 기억하지 않고 일치를 강조할 수 있다.
 *
 * @property documentId 일치를 찾은 문서.
 * @property snippet 결과 행에 표시할 주변 텍스트.
 * @property location 결과를 탭할 때 리더가 이동할 위치.
 * @property sectionTitle 일치가 위치한 섹션의 제목이며 해당 섹션에 제목이 없으면 `null`.
 * @property range 강조에 사용할 일치의 정확한 절대 문서 오프셋 범위.
 * @property query 검색한 내용으로, 행이 따로 기억하지 않고 강조할 수 있도록 함께 전달한다.
 */
@Serializable
data class SearchResult(
    val documentId: DocumentId,
    val snippet: String,
    val location: ReaderLocation,
    val sectionTitle: String? = null,
    val range: TextRange? = null,
    val query: String = "",
)

/**
 * 이 문서를 검색하여 결과를 얻을 수 있는지 나타낸다. 재배치 가능 텍스트가 있고 그 텍스트가 실제로 존재해야 한다.
 *
 * 두 번째 조건은 책을 가져오는 중에 중요하다. 섹션은 있지만 아직 공백인 문서에 검색을 제공하면 "결과 없음"만 답할 수 있기 때문이다.
 *
 * @receiver 판단할 문서.
 * @return 형식이 텍스트를 재배치하고 하나 이상의 섹션에 실제 텍스트가 있어 검색으로 무언가 찾을 수 있을 때만 `true`.
 */
fun ReaderDocument.isTextSearchSupported(): Boolean =
    !format.isVisualPageFormat() && sections.any { section -> section.text.isNotBlank() }

/**
 * 통계 화면에서 차트로 표시할 문서 하나의 하루 독서량이다.
 *
 * 세션별이 아니라 달력 날짜별로 집계한다. 차트가 표시하는 단위이기 때문이다. 아직 이를 생성하는 기능은 없다. 독서 세션을 어디에도 기록하지 않으므로(ReadingStatsRepository 참고), 이는 기능이 읽을 형태일 뿐 실제 데이터가 있는 형태는 아니다.
 *
 * @property documentId 읽은 문서.
 * @property date 차트가 표시하는 단위인 달력 날짜.
 * @property activeMillis 해당 날짜에 실제로 읽은 시간.
 * @property wordsRead 해당 날짜에 읽은 단어 수.
 * @throws IllegalArgumentException [activeMillis] 또는 [wordsRead]가 음수인 경우.
 */
@Serializable
data class ReadingHistoryEntry(
    val documentId: DocumentId,
    val date: LocalDate,
    val activeMillis: Long,
    val wordsRead: Long,
) {
    init {
        require(activeMillis >= 0L) { "activeMillis must be positive." }
        require(wordsRead >= 0L) { "wordsRead must be positive." }
    }
}

/**
 * 중간 할당 없이 왼쪽에서 오른쪽으로 한 번 순회하여 이 텍스트의 공백 구분 단어 수를 계산한다. 단어 경계는 공백 문자에서 공백이 아닌 문자로 바뀌는 모든 지점이다. 연속된 모든 종류의 공백은 하나의 구분자로 합쳐지며, 앞뒤 공백은 단어에 진입할 때를 제외하고 개수를 늘리지 않으므로 사실상 무시된다.
 *
 * 공백은 [Char.isWhitespace]로 정의한다. 이는 ASCII 제어 공백(공백, 탭, 줄 바꿈 문자, 세로 탭, 폼 피드, 캐리지 리턴)과 Unicode category-Zs 문자(줄 바꿈 없는 공백, en/em 공백, 표의문자 공백 등)를 포함한다. JVM의 기존 Java `Regex("\\s+")` 기반 `split`은 ASCII 공백만 인식한 반면 Native에서는 ICU를 통해 Unicode 공백도 인식하여 이전 구현에 조용한 플랫폼 간 불일치가 있었다. 이 방식은 JVM과 Kotlin/Native에서 동작을 일치시킨다.
 *
 * @receiver 단어 수를 계산할 텍스트.
 * @return 비어 있거나 공백뿐인 텍스트면 0이고, 그 외에는 하나 이상의 공백 문자로 구분한 공백 아닌 토큰의 수.
 */
fun String.wordCount(): Int {
    var count = 0
    var inWord = false
    for (ch in this) {
        if (ch.isWhitespace()) {
            inWord = false
        } else {
            if (!inWord) count++
            inWord = true
        }
    }
    return count
}
