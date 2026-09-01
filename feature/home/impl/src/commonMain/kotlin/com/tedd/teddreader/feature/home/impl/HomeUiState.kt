package com.tedd.teddreader.feature.home.impl

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import com.tedd.teddreader.core.common.model.DocumentMetadata
import com.tedd.teddreader.core.ui.system.DisplayFold
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList

/**
 * [HomeViewModel][com.tedd.teddreader.feature.home.impl.HomeViewModel]이 발행하고
 * [HomeScreen][com.tedd.teddreader.feature.home.impl.HomeScreen]이 렌더링하는 홈/라이브러리 화면의 전체
 * 스냅샷이다. 즐겨찾기와 최근 읽기 선반, 라이브러리 그리드와 폴더, 화면이 사용자에게 보여 주는
 * 정렬/필터/로딩/오류 상태를 담는다.
 *
 * [libraryDocuments]와 [documentCoverImages]는 모두 이미 [formatFilter]로 범위가 좁혀지고 [sort]에
 * 따라 정렬되어 있다. [libraryFolders]는 의도적으로 필터와 관계없이 모든 문서에서 만들어지므로 현재
 * 표시되는 문서 중 해당 폴더에 속한 문서가 없다는 이유만으로 폴더가 사라지지 않는다. [hasDocuments]는
 * 빈 [libraryDocuments]와 다른 질문에 답한다. 라이브러리에 무엇이든 하나라도 있으면 true를 유지하여
 * 화면이 "한 번도 가져오지 않음"과 "현재 필터에 일치하는 항목 없음"을 구분할 수 있게 한다.
 *
 * @property favoriteDocuments [libraryDocuments]와 같은 방식으로 필터링하고 정렬한 즐겨찾기 문서.
 * @property recentDocuments [formatFilter]에 일치하는 즐겨찾기 아닌 문서 중 가장 최근 20개. 각 문서를
 *   마지막으로 연 시각순이며 이 순서는 고정되어 [sort]를 따르지 않는다.
 * @property libraryDocuments [formatFilter]에 일치하는 모든 문서를 [sort] 순서로 담은 목록.
 * @property libraryFolders 필터링하지 않은 전체 문서 목록에서 계산한 라이브러리 폴더. [formatFilter]가
 *   폴더의 모든 내용을 숨기는 동안에도 폴더는 계속 표시된다.
 * @property documentCoverImages 문서 id를 키로 하는 디코딩된 표지 바이트. [formatFilter]를 통과한
 *   문서만 보관하며, 이미 가져왔더라도 이제 숨겨진 문서의 표지는 화면 상태에서 제거한다.
 * @property hasDocuments [formatFilter]와 관계없이 라이브러리에 문서가 하나라도 있는지 여부. 빈
 *   라이브러리와 일치 항목이 없는 필터를 구분한다.
 * @property sort 화면에서 선택된 옵션을 표시할 수 있도록 되돌려 주는 현재 라이브러리 정렬 순서.
 * @property formatFilter [sort]와 같은 이유로 되돌려 주는 현재 라이브러리 형식 제한.
 * @property isLoading 문서 목록을 한 번 이상 읽을 때까지 true.
 * @property errorMessage 라이브러리 로드 또는 즐겨찾기, 삭제, 폴더 쓰기 작업이 가장 최근에 실패했으면
 *   null이 아닌 오류 메시지.
 * @property unsupportedFormatMessage 지원하지 않는 파일처럼 앱의 다른 곳에서 처리하지 못한 가져오기에
 *   관한 메시지. [HomeViewModel][com.tedd.teddreader.feature.home.impl.HomeViewModel] 자체는 항상 null을
 *   발행하며, `HomeRouteScreen`이 화면에 상태를 전달하기 전에 해당 가져오기 결과에서 이 값을 채운다.
 */
@Immutable
data class HomeUiState(
    val favoriteDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val recentDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryDocuments: ImmutableList<DocumentMetadata> = persistentListOf(),
    val libraryFolders: ImmutableList<LibraryFolder> = persistentListOf(),
    val documentCoverImages: ImmutableMap<String, ByteArray> = persistentMapOf(),
    val hasDocuments: Boolean = false,
    val sort: HomeSort = HomeSort.Recent,
    val formatFilter: HomeFormatFilter = HomeFormatFilter.All,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val unsupportedFormatMessage: String? = null,
)

/**
 * 라이브러리의 Folders 보기에 표시되는 폴더 하나다. 폴더에 속한 모든 문서를 로드하지 않고도 폴더 타일에
 * 필요한 식별 정보와 개수를 제공한다.
 *
 * [buildLibraryFolders]가 라이브러리 문서의 서로 다른 폴더 id마다 하나씩 만든다. 문서의 폴더 id와 폴더
 * 이름을 모두 알 때만 해당 문서가 여기에 기여하는 이유는 그 함수를 참고한다.
 *
 * @property id 폴더에 속한 문서의 `DocumentMetadata.folderId`와 일치하는 폴더 식별자.
 * @property name 폴더의 표시 이름.
 * @property documentCount 이 폴더에 속한 문서 수. 미리보기 자체의 크기와 함께 아직 표시하지 않은 개수를
 *   계산하는 데 사용한다([libraryFolderRemainingDocumentCount] 참고).
 */
@Immutable
data class LibraryFolder(
    val id: String,
    val name: String,
    val documentCount: Int,
)

/**
 * 라이브러리 목록과 즐겨찾기를 정렬하는 방법이다. [HomeUiState.sort]에서 최근순, 제목 알파벳순 또는
 * 형식과 제목순 중 하나를 선택하며, `HomeViewModel`이 [HomeUiState.libraryDocuments]나
 * [HomeUiState.favoriteDocuments]를 만드는 모든 곳에 적용한다.
 */
enum class HomeSort {
    Recent,
    Title,
    Format,
}

/**
 * 라이브러리와 표지 캐시를 하나의 `DocumentFormat`으로 제한하거나 [All]로 제한을 해제한다.
 * [HomeUiState.formatFilter]에서 선택하고 [HomeUiState]의 모든 문서 목록에 적용한다. 단,
 * [HomeUiState.libraryFolders]는 의도적으로 필터링하지 않은 전체 문서 목록에서 만든다.
 */
enum class HomeFormatFilter {
    All,
    Txt,
    Pdf,
    Epub,
    Comic,
    Image,
}

/**
 * [HomeDocumentActionTarget]이 속한 선반을 나타낸다. 문서는 보통 [HomeUiState.libraryDocuments]와,
 * 같은 문서 id로 [HomeUiState.favoriteDocuments] 또는 [HomeUiState.recentDocuments] 중 자격을 갖춘
 * 한 곳에 함께 나타난다. 이 태그가 같은 id를 공유하는 두 행을 구분하므로 한 카드의 더보기 메뉴를 열어도
 * 다른 선반의 중복 카드 메뉴가 함께 열리지 않는다.
 */
internal enum class HomeDocumentSection {
    Favorites,
    Recent,
    Library,
}

/**
 * 정확히 어느 문서 행의 더보기 메뉴가 열렸는지 식별한다. 같은 문서 id가 여러 선반에 동시에 표시될 수
 * 있으므로([HomeDocumentSection] 참고) 문서 자체뿐 아니라 문서가 속한 선반도 함께 담는다.
 *
 * @property section 열린 메뉴가 속한 선반.
 * @property documentId 메뉴가 열린 문서.
 */
internal data class HomeDocumentActionTarget(
    val section: HomeDocumentSection,
    val documentId: String,
)

/**
 * 라이브러리 미리보기에 개별 문서와 폴더 중 무엇을 표시할지 나타낸다. 홈 화면 라이브러리 섹션의
 * All/Folders 칩으로 전환하며 전용 라이브러리 화면에도 동일하게 반영된다.
 */
enum class LibraryCollectionMode {
    All,
    Folders,
}

/**
 * "show all"로 넘기기 전에 홈 화면 미리보기 그리드가 표시할 수 있는 라이브러리 항목 수다. 기기가 실제로
 * 제공하는 화면 크기에 따라 선택한다. 확장 창, 태블릿 너비의 최단 변 또는 화면을 나누는 fold가 있는
 * 폴더블은 더 넓은 제한을 사용하고 일반 휴대전화는 더 좁은 제한을 사용한다. 실제 화면 크기 계산
 * ([libraryPreviewLimit])과 규칙을 분리하여 실제 창을 측정하지 않고도 테스트에서 직접 검증할 수 있게 했다.
 *
 * @param isExpanded 창 크기 클래스가 expanded인지 여부.
 * @param isTablet 최단 변이 태블릿 너비 이상인지 여부.
 * @param hasSeparatingFold 기기가 화면을 두 pane으로 나누는 fold를 보고하는지 여부.
 * @return 셋 중 하나라도 레이아웃을 넓히면 8, 그렇지 않으면 4.
 */
internal fun homeLibraryPreviewLimit(
    isExpanded: Boolean,
    isTablet: Boolean,
    hasSeparatingFold: Boolean,
): Int = if (isExpanded || isTablet || hasSeparatingFold) 8 else 4

/**
 * 홈 화면이 실제로 호출하는 형태의 [homeLibraryPreviewLimit]이다. 측정한 최단 변에서 `isTablet`을,
 * 기기의 fold 보고에서 `hasSeparatingFold`를 구한다. 홈 화면 자체에는 expanded 창 사례가 없으므로
 * `isExpanded`는 항상 false로 둔다.
 *
 * 이 함수는 `@Composable`이 아닌 순수 함수이므로 테마의 breakpoint를 직접 읽을 수 없다.
 * `teddReaderBreakpoints()`에는 composition이 필요하며, 대신 정적 `DefaultTeddReaderBreakpoints`를
 * 사용하면 테마 재정의를 조용히 무시하게 된다. 따라서 테마 안에서 compose하는 호출자가 [tabletMinWidth]로
 * 임계값을 전달한다. 이 방식으로 함수 자체를 `@Composable`로 만들지 않고도 테마를 따를 수 있다.
 *
 * @param shortestSide 호출자가 측정한 창의 최단 변.
 * @param displayFold 기기의 현재 fold 상태. 접히지 않는 기기에서는 null.
 * @param tabletMinWidth 호출자를 태블릿 크기로 취급할 최단 변 너비의 최솟값. 호출자는 테마의
 *   `TeddReaderBreakpoints.medium`을 전달한다.
 */
internal fun libraryPreviewLimit(
    shortestSide: Dp,
    displayFold: DisplayFold?,
    tabletMinWidth: Dp,
): Int = homeLibraryPreviewLimit(
    isExpanded = false,
    isTablet = shortestSide >= tabletMinWidth,
    hasSeparatingFold = displayFold?.isVertical == true && displayFold.isSeparating,
)

/**
 * 홈 화면의 "All" 라이브러리 미리보기에 표시할 앞쪽 [previewLimit]개 문서다. 호출자가 이미 정렬한 순서를
 * 유지하며 목록 크기만 줄이고 다시 정렬하지 않는다.
 *
 * @param documents 미리보기에 사용할 이미 정렬된 문서.
 * @param previewLimit 유지할 문서 수. [homeLibraryPreviewLimit] 참고.
 */
internal fun homeLibraryPreviewDocuments(
    documents: List<DocumentMetadata>,
    previewLimit: Int,
): ImmutableList<DocumentMetadata> = documents.take(previewLimit).toImmutableList()

/**
 * 미리보기 그리드가 실제로 짧은 마지막 행의 몇 개 항목을 행 전체 너비로 늘리지 않고 각 행에 같은 너비의
 * 셀을 정확히 [columns]개 배치하도록 [items]를 고정 너비 행으로 묶는다.
 *
 * 짧은 마지막 행은 그대로 두지 않고 [columns]까지 `null`로 채운다. 호출자는 `null` 슬롯을 해당 열의
 * weight를 유지하는 빈 spacer로 렌더링하므로 실제 항목이 위쪽의 완전한 행과 같은 너비와 정렬을 유지한다.
 * 이 함수의 반환 타입만 보는 호출자는 이 형태를 단순히 항목 수가 적은 행과 구분할 수 없다. 여기의 모든
 * 행은 정확히 [columns] 길이이며 `null` 항목은 "항목 없음, 공간 유지"를 뜻한다.
 *
 * @param items 표시할 순서대로 정렬된 항목.
 * @param columns 각 행의 너비. 양수여야 한다.
 * @return 각 행이 정확히 [columns]개 항목인 행 목록. 마지막 행에서 채울 항목이 부족한 슬롯은 `null`이다.
 * @throws IllegalArgumentException [columns]가 양수가 아닐 때.
 */
internal fun <T : Any> homeLibraryGridRows(
    items: List<T>,
    columns: Int,
): List<List<T?>> {
    require(columns > 0) { "columns must be positive." }
    return items.chunked(columns).map { row ->
        row.map<T, T?> { it } + List(columns - row.size) { null }
    }
}

/**
 * [documents] 자체의 순서에서 [folderId]에 속한 앞쪽 [previewLimit]개 문서다. 폴더 전체 내용을 로드하지
 * 않고 폴더 표지 타일에 표시할 썸네일이다.
 *
 * @param documents 썸네일로 선택될 순서대로 정렬된 검색 대상 라이브러리 문서.
 * @param folderId 문서를 모을 폴더.
 * @param previewLimit 유지할 문서 수.
 */
internal fun libraryFolderPreviewDocuments(
    documents: List<DocumentMetadata>,
    folderId: String,
    previewLimit: Int,
): ImmutableList<DocumentMetadata> =
    documents.filter { it.folderId == folderId }.take(previewLimit).toImmutableList()

/**
 * 폴더 표지 타일이 썸네일과 함께 표시하는 "+N more" 레이블을 위해 미리보기에 나오지 않은 폴더 문서 수를
 * 계산한다. 작은 폴더의 모든 문서가 미리보기에 들어가 [previewCount]가 이미 [totalCount]와 같을 수
 * 있으므로 음수가 되지 않게 0을 최솟값으로 둔다.
 *
 * @param totalCount 폴더에 실제로 들어 있는 문서 수.
 * @param previewCount 미리보기에 이미 표시 중인 문서 수.
 * @return [totalCount]에서 [previewCount]를 뺀 값. 음수가 되지 않는다.
 */
internal fun libraryFolderRemainingDocumentCount(
    totalCount: Int,
    previewCount: Int,
): Int = (totalCount - previewCount).coerceAtLeast(0)

/**
 * [documents]에서 서로 다른 폴더 id마다 [LibraryFolder] 하나를 만들고, 폴더에 속한 문서에서 이름과 개수를
 * 구해 안정적인 표시 순서로 정렬한 라이브러리 폴더 목록이다.
 *
 * 폴더 id와 폴더 이름이 모두 있는 문서만 여기에 폴더를 제공한다. `DocumentMetadata` 자체는 둘이 모두
 * null이거나 모두 null이 아니어야 한다(자체 `@throws` 참고). 따라서 실제 인스턴스에서 둘이 불일치할 수
 * 없지만, 둘 다 검사하면 다른 곳에서 해당 불변식이 유지된다는 사실에 이 함수의 정확성이 암묵적으로
 * 의존하지 않으면서 "표시할 가치가 있는 폴더에는 id와 이름이 모두 필요하다"라는 실제 의도를 드러낸다.
 * 아래 몇 줄의 `firstOrNull()?.folderName` 읽기도 같은 주의를 한 번 더 적용한다. 불변식 때문에 불가능하다고
 * 가정하지 않고 그룹에서 이름을 얻지 못한 폴더 id를 건너뛴다.
 *
 * @param documents 폴더를 도출할 문서. 폴더가 없는 문서는 기여하지 않는다.
 * @return 서로 다른 폴더 id마다 하나씩 생성하고 이름의 대소문자를 구분하지 않고 정렬한 [LibraryFolder].
 */
internal fun buildLibraryFolders(documents: List<DocumentMetadata>): List<LibraryFolder> =
    documents
        .filter { it.folderId != null && it.folderName != null }
        .groupBy { it.folderId!! }
        .mapNotNull { (folderId, folderDocuments) ->
            val folderName = folderDocuments.firstOrNull()?.folderName ?: return@mapNotNull null
            LibraryFolder(
                id = folderId,
                name = folderName,
                documentCount = folderDocuments.size,
            )
        }
        .sortedBy { it.name.lowercase() }
