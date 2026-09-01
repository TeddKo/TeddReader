package com.tedd.teddreader.core.data.mapper

import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.PageIndex
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderLocation
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.SearchResult
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.parseReaderLocation
import com.tedd.teddreader.core.domain.repository.Bookmark
import com.tedd.teddreader.core.domain.repository.ReadingSession
import com.tedd.teddreader.core.domain.repository.ReadingProgress
import com.tedd.teddreader.core.room.dao.SearchIndexSearchEntry
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity
import kotlinx.serialization.json.Json

/**
 * 저장된 읽기 위치를 리더가 실제로 다루는 도메인 [ReadingProgress]로 되살린다. Room이 저장하는 평평한
 * [ReaderLocation] 문자열을 다시 원래의 타입 있는 형태로 파싱한다.
 *
 * @receiver 한 문서의 저장된 읽기 위치에 대한 Room 행.
 * @return 그에 대응하는 [ReadingProgress].
 */
fun ReadingProgressEntity.toReadingProgress(): ReadingProgress = ReadingProgress(
    documentId = DocumentId(documentId),
    location = parseReaderLocation(readerLocation),
    pageIndex = PageIndex(currentPageIndex, totalPageCount ?: 0),
    updatedAtEpochMillis = updatedAtEpochMillis,
)

/**
 * [ReadingProgress]를 Room이 저장하는 행 형태로 평탄화한다. [toReadingProgress]의 역함수다.
 * [ReaderLocation]은 Room 친화적인 표현을 자체적으로 갖고 있지 않으므로, 컬럼이 되기 전에 [asStorageString]을
 * 통해 평문 문자열로 직렬화된다.
 *
 * @receiver 저장할 읽기 위치.
 * @return 그에 대응하는 [ReadingProgressEntity] 행.
 */
fun ReadingProgress.toReadingProgressEntity(): ReadingProgressEntity = ReadingProgressEntity(
    documentId = documentId.value,
    readerLocation = location.asStorageString(),
    currentPageIndex = pageIndex.current,
    totalPageCount = pageIndex.total,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

/**
 * 저장된 북마크 행을 리더가 표시하는 도메인 [Bookmark]로 되살린다.
 *
 * @receiver 저장된 북마크 하나에 대한 Room 행.
 * @return 그에 대응하는 [Bookmark].
 */
fun BookmarkEntity.toBookmark(): Bookmark = Bookmark(
    id = id,
    documentId = DocumentId(documentId),
    location = parseReaderLocation(readerLocation),
    label = label,
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
)

/**
 * [Bookmark]를 Room이 저장하는 행 형태로 평탄화한다. [toBookmark]의 역함수다.
 *
 * @receiver 저장할 북마크.
 * @return 그에 대응하는 [BookmarkEntity] 행.
 */
fun Bookmark.toBookmarkEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    documentId = documentId.value,
    readerLocation = location.asStorageString(),
    label = label,
    note = note,
    createdAtEpochMillis = createdAtEpochMillis,
)

/**
 * 기록된 읽기 세션 하나를, 읽기 통계 계산의 기반이 되는 도메인 [ReadingSession]으로 되살린다. `endLocation`은
 * 기록된 종료 위치가 없는 세션이라면 어떤 대체값으로 파싱되는 대신 `null`로 남는다.
 *
 * @receiver 읽기 세션 하나에 대한 Room 행.
 * @return 그에 대응하는 [ReadingSession].
 */
fun ReadingSessionEntity.toReadingSession(): ReadingSession = ReadingSession(
    id = id,
    documentId = DocumentId(documentId),
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    activeMillis = activeMillis,
    startLocation = parseReaderLocation(startLocation),
    endLocation = endLocation?.let(::parseReaderLocation),
)

/**
 * [ReadingSession]을 Room이 저장하는 행 형태로 평탄화한다. [toReadingSession]의 역함수다.
 *
 * @receiver 저장할 읽기 세션.
 * @return 그에 대응하는 [ReadingSessionEntity] 행.
 */
fun ReadingSession.toReadingSessionEntity(): ReadingSessionEntity = ReadingSessionEntity(
    id = id,
    documentId = documentId.value,
    startedAtEpochMillis = startedAtEpochMillis,
    endedAtEpochMillis = endedAtEpochMillis,
    activeMillis = activeMillis,
    startLocation = startLocation.asStorageString(),
    endLocation = endLocation?.asStorageString(),
)

/**
 * 문서가 임포트되거나 복구되는 시점에 한 섹션이 저장되는 검색 인덱스 행을 만든다. 섹션 자신의 텍스트와
 * 오프셋은 그대로 넘어가고, [blocks]와 [navigation]은 Room에 대응하는 컬럼 타입이 없어서 JSON으로
 * 직렬화된다. [documentTitle]은 (따로 조회하는 대신) 모든 섹션의 행에 중복 저장되어, 검색 결과가 join
 * 없이도 어느 책에서 왔는지 보여줄 수 있게 한다. 이 행에는 [CurrentReaderParserVersion]으로 태그가 붙는데,
 * 이후 파서가 업그레이드될 때 어떤 행이 예전 파서로 기록되어 재임포트가 필요한지 알 수 있게 하기 위해서다.
 *
 * @receiver 인덱싱되는 섹션.
 * @param documentId 이 섹션이 속한 문서.
 * @param blocks 이 섹션의 블록 구조. JSON으로 직렬화되어 행에 저장된다.
 * @param documentTitle 소유 문서의 제목. 표시를 위해 이 행에 비정규화되어 저장된다. 호출자가 아직 갖고
 *   있지 않으면 `null`.
 * @param navigation 문서의 목차. JSON으로 직렬화되어 행에 저장된다. 호출자가 아직 갖고 있지 않으면 `null`이며,
 *   이 경우 JSON `null`이 아니라 빈 문자열로 저장된다.
 * @param json [blocks]와 [navigation]을 직렬화하는 데 쓰이는 [Json] 인스턴스.
 * @param sourcePath 이 섹션이 파싱된 spine 항목의 아카이브 상대 경로. `finishEpubImport`가 모든 섹션
 *   텍스트를 다시 읽지 않고도 목차를 해석할 수 있도록 저장된다. EPUB가 아닌 문서라면 null.
 * @return 이 섹션에 대해 업서트할 [SearchIndexEntity] 행.
 */
fun ReaderSection.toSearchIndexEntity(
    documentId: DocumentId,
    blocks: List<ReaderBlock> = emptyList(),
    documentTitle: String? = null,
    navigation: ReaderNavigation? = null,
    json: Json = Json,
    sourcePath: String? = null,
): SearchIndexEntity = SearchIndexEntity(
    documentId = documentId.value,
    sectionIndex = index,
    sectionTitle = title,
    text = text,
    startOffset = range.start,
    endOffset = range.end,
    blocksJson = json.encodeToString(blocks),
    documentTitle = documentTitle,
    navigationJson = navigation?.let { json.encodeToString(it) }.orEmpty(),
    parserVersion = CurrentReaderParserVersion,
    sourcePath = sourcePath,
)

/**
 * 파서가 리더에게 필요하지만 예전에 저장된 텍스트에는 없는 뭔가를 만들어내기 시작할 때마다 올라간다 —
 * 이미지 비율, 스타일시트에서 유도한 블록 스타일, 문장 안에 유지되는 그림 등. 예전 빌드가 기록한 저장된
 * 행은 다음에 책을 열 때 파일에서 다시 읽힌다.
 *
 * 버전 2는 섹션 상대 블록 저장이다(DocumentRepositoryImpl.persistParsedDocument/
 * importNextSections). 이 버전으로 올리는 것은, `repairEpubDocument`가 여전히 파일 전체를 읽고 리더가
 * 그리기 전에 모든 챕터를 파싱하던 동안에는 보류됐다. 그렇게 했다면 서가에 이미 있던 모든 책이 다음에
 * 열릴 때 20~40초의 지연을 겪었을 것이다. 그 경로는 이제 새로 고른 EPUB가 거치는 것과 같은 단계적
 * 임포트를 거치므로, 이 버전 아래의 책도 새 책만큼 빠르게 첫 챕터를 보여주고 나머지는 백그라운드에서
 * 마무리한다 — 이것이 버전을 올리는 비용을 앞으로도 최소한으로 유지하는 이유다.
 *
 * 버전 3은 인라인 CSS 스팬 보존과 float 이미지 폴백/너비 복구를 추가하므로, 오래된 저장 블록은 이미
 * 임포트된 EPUB이 그 수정 사항을 렌더링하기 전에 다시 파싱되어야 한다.
 *
 * 버전 4는 퍼블리셔 색상/글꼴/박스 스타일링, 숨겨진 하위 트리 제거, 장식된 컨테이너 범위, 인라인
 * float 이미지 보존을 추가하므로, 예전 저장 섹션은 이 더 풍부한 블록과 스팬을 되살리기 위해 다시
 * 파싱되어야 한다.
 *
 * 버전 5는 body/html 배경 컨테이너와 퍼블리셔 float/테두리/색상 디코딩의 형태가 바뀌면서 그 더 풍부한
 * 스키마를 다시 올린다. 이 버전 아래의 저장 행은 그 블록들이 일관되게 나타나기 전에 복구되어야 한다.
 *
 * 복구는 책의 텍스트를 다시 읽으므로 문자 오프셋이 이동할 수 있다. 그 뒤에 이어지는 문자 수 불일치로
 * 저장된 페이지 레이아웃이 폐기되고(DocumentRepositoryImpl.restorePageWindows 참고), 읽기 위치는 정확한
 * 페이지가 아니라 가장 가까운 페이지에 놓인다. 그것이 버전을 올리는 대가이며, 리더에게 실제로 필요하지
 * 않은 것을 위해 버전을 올리지 않는 이유다.
 */
const val CurrentReaderParserVersion: Int = 9

/**
 * 이 섹션에 저장된 텍스트 안에서 [query]가 겹치지 않게 나타나는 모든 위치를, 문서 순서대로.
 *
 * 한 섹션의 행은 같은 단어를 여러 번 담을 수 있고, 각 위치는 섹션당 하나의 결과가 아니라 그 자체로
 * 하나의 [SearchResult]가 되어, 호출자가 그중 어디로든 바로 이동할 수 있게 한다. 매칭은 대소문자를
 * 구분하지 않으며 다음 매치를 찾기 전에 각 매치를 지나쳐 진행하므로, 한 위치가 앞선 위치와 겹치는 일은
 * 없다("aaa"에서 "aa"를 검색하면 매치가 둘이 아니라 하나 나온다). 빈 [query]는 매번 0글자씩 진행하며
 * 무한히 도는 것을 막기 위해 미리 거부되어 결과 없이 끝난다.
 *
 * @receiver 검색할 저장된 섹션 행.
 * @param query 찾을 텍스트. 결과가 나오려면 비어 있지 않아야 한다.
 * @param limit 이 섹션에서 구체화할 최대 매치 개수. 0 이하 값은 즉시 반환되며, 이는 문서 전체를 훑는
 *   호출자가 남은 결과 예산이 소진된 밀집 섹션을 폐기할 매치를 만들지 않고 곧바로 멈출 수 있게 한다.
 * @return [text] 안에서 나타나는 순서대로, [limit]까지 잘라낸 매치된 [SearchResult]들. [query]가 비어
 *   있거나 [limit]이 0 이하이거나 매치가 전혀 없으면 빈 목록.
 */
fun SearchIndexSearchEntry.toSearchResults(
    query: String,
    limit: Int = Int.MAX_VALUE,
): List<SearchResult> {
    if (query.isEmpty() || limit <= 0) return emptyList()
    return buildList(capacity = minOf(limit, 16)) {
        var searchStartIndex = 0
        while (size < limit && searchStartIndex <= text.length - query.length) {
            val matchIndex = text.indexOf(query, startIndex = searchStartIndex, ignoreCase = true)
            if (matchIndex < 0) break

            val matchStart = startOffset + matchIndex
            val matchEnd = (matchStart + query.length).coerceAtMost(endOffset)
            add(
                SearchResult(
                    documentId = DocumentId(documentId),
                    snippet = text.snippetAround(matchIndex, query.length),
                    location = ReaderLocation.TextOffset(matchStart),
                    sectionTitle = sectionTitle,
                    range = TextRange(matchStart, matchEnd),
                    query = query,
                ),
            )
            searchStartIndex = matchIndex + query.length
        }
    }
}

/**
 * 검색 결과가 매치된 단어 하나만 보여주거나 섹션 전체 텍스트를 보여주는 대신, 문맥을 담은 스니펫처럼
 * 읽히도록 매치 주변에 보여주는 텍스트 범위다.
 *
 * @receiver 매치가 발견된 섹션 텍스트.
 * @param matchIndex 이 문자열 안에서 매치가 시작하는 인덱스.
 * @param matchLength 매치된 텍스트의 길이.
 * @return 매치 양쪽으로 최대 [SNIPPET_RADIUS] 글자. 매치가 어느 한쪽 끝에 가까우면 패딩되지 않고 이
 *   문자열의 경계에서 잘린다.
 */
private fun String.snippetAround(matchIndex: Int, matchLength: Int): String {
    val start = (matchIndex - SNIPPET_RADIUS).coerceAtLeast(0)
    val end = (matchIndex + matchLength + SNIPPET_RADIUS).coerceAtMost(length)
    return substring(start, end)
}

/** [snippetAround]가 매치 양쪽에 유지하는 문맥 문자 수. */
private const val SNIPPET_RADIUS = 40
