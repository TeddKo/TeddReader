package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderDocument
import kotlin.random.Random
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import org.koin.core.annotation.Single

/**
 * CBZ(페이지 이미지들의 ZIP)를 페이지 수가 매겨진 [ReaderDocument]로 읽고, 필요할 때 호출자가 실제로
 * 필요로 하는 페이지 — 라이브러리에 보여줄 표지든, 읽는 동안 표시할 페이지든 — 의 바이트를 디코딩한다.
 * 모든 페이지를 미리 디코딩하는 일은 절대 없다 — [parse]는 몇 개의 항목이 페이지로 인정되는지만 세고,
 * 실제 페이지 이미지 바이트는 [pageImageBytes]와 [coverImageBytes]가 지연 방식으로 읽는다. 여기에는
 * 텍스트가 전혀 없다 — [ReaderDocument.sections]는 항상 비어 있다 — 만화는 흘러가는 산문이 아니라
 * 페이지 이미지의 연속으로 읽히기 때문이다.
 */
@Single
open class ComicBookDocumentParser {
    /**
     * [bytes]에 담긴 CBZ의 페이지 수를 세고 그 문서를 만든다.
     *
     * @param id 변경 없이 그대로 전달되는 원본 파일의 식별자.
     * @param title 문서에 표시될 레이블; 아카이브에서 유도되지 않는다.
     * @param bytes CBZ의 원시 콘텐츠. ZIP으로 열 수 있도록 임시 파일에 쏟아붓는다([withComicZip] 참고).
     * @return [DocumentFormat.CBZ]의 [ReaderDocument]. 섹션은 없으며 페이지 수는
     *   [sortedComicPageNames]가 페이지로 인식하는 항목 수와 같다.
     * @throws IllegalArgumentException 아카이브에 [sortedComicPageNames]가 페이지로 인식하는 항목이
     *   하나도 없을 때 — 비어 있거나 만화가 아닌 ZIP은 이 리더가 보여줄 것이 없다.
     */
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument = withComicZip(bytes) { archive ->
        val pageCount = archive.pageCount
        require(pageCount > 0) { "CBZ contains no supported image pages." }
        comicReaderDocument(id = id, title = title, pageCount = pageCount)
    }

    /**
     * [path]에 있는 CBZ의 페이지 수를 세고 그 문서를 만든다.
     *
     * @param id 변경 없이 그대로 전달되는 원본 파일의 식별자.
     * @param title 문서에 표시될 레이블; 아카이브에서 유도되지 않는다.
     * @param path CBZ 파일의 위치. 임시 복사 없이 ZIP으로 직접 연다.
     * @return [DocumentFormat.CBZ]의 [ReaderDocument]. 섹션은 없으며 페이지 수는
     *   [sortedComicPageNames]가 페이지로 인식하는 항목 수와 같다.
     * @throws IllegalArgumentException 아카이브에 [sortedComicPageNames]가 페이지로 인식하는 항목이
     *   하나도 없을 때.
     */
    fun parse(
        id: DocumentId,
        title: String,
        path: Path,
    ): ReaderDocument = withComicZip(path) { archive ->
        val pageCount = archive.pageCount
        require(pageCount > 0) { "CBZ contains no supported image pages." }
        comicReaderDocument(id = id, title = title, pageCount = pageCount)
    }

    /**
     * 첫 페이지의 이미지 바이트 — CBZ는 EPUB나 PDF처럼 표지 메타데이터를 담지 않으므로, 이 포맷에서는
     * 이것이 표지를 대신한다.
     *
     * @param bytes CBZ의 원시 콘텐츠.
     * @return 첫 페이지의 바이트, 또는 아카이브의 인덱스 0에 인식 가능한 페이지가 없거나 그 페이지의
     *   바이트를 읽을 수 없었다면 null([pageImageBytes] 참고).
     */
    fun coverImageBytes(bytes: ByteArray): ByteArray? =
        pageImageBytes(bytes, setOf(0))[0]

    /**
     * [path]에 있는 CBZ에서 읽은 첫 페이지의 이미지 바이트. null이 무엇을 의미하는지는 (bytes 오버로드인)
     * [coverImageBytes]를 참고.
     *
     * @param path CBZ 파일의 위치.
     */
    fun coverImageBytes(path: Path): ByteArray? =
        pageImageBytes(path, setOf(0))[0]

    /**
     * [bytes]에 담긴 CBZ에서 [pageIndexes]에 해당하는 페이지들의 이미지 바이트를 읽는다.
     *
     * @param bytes CBZ의 원시 콘텐츠.
     * @param pageIndexes [comicPagePaths]의 읽기 순서 기준 0부터 시작하는 페이지 번호들; 빈 집합이면
     *   아카이브를 열지도 않고 바로 반환한다.
     * @return 페이지 인덱스를 키로 하는 바이트 맵. 실제로 존재했고 항목을 실제로 읽을 수 있었던
     *   인덱스만 포함한다 — 마지막 페이지를 넘어선 인덱스나, 손상되었거나 지나치게 크거나
     *   ([MaxComicPageBytes] 참고) 그 밖의 이유로 읽을 수 없는 페이지 항목은 전체 호출을 실패시키는
     *   대신 그냥 결과에서 빠진다.
     */
    fun pageImageBytes(
        bytes: ByteArray,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return withComicZip(bytes) { archive ->
            archive.pageImageBytes(pageIndexes)
        }
    }

    /**
     * [path]에 있는 CBZ에서 [pageIndexes]에 해당하는 페이지들의 이미지 바이트를 읽는다. 결과에 무엇이
     * 포함되는지는 [pageImageBytes]의 [bytes] 오버로드를 참고.
     *
     * @param path CBZ 파일의 위치.
     * @param pageIndexes 읽을, 0부터 시작하는 페이지 번호들; 빈 집합이면 아카이브를 열지 않고 바로
     *   반환한다.
     */
    fun pageImageBytes(
        path: Path,
        pageIndexes: Set<Int>,
    ): Map<Int, ByteArray> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return withComicZip(path) { archive ->
            archive.pageImageBytes(pageIndexes)
        }
    }

    /**
     * 이미 디스크에 있는 [path]의 CBZ를 재사용 가능한 [ComicArchive]로 연다. 그 Okio ZIP [FileSystem]과
     * 자연 순서로 정렬된 페이지 경로들은 한 번만 구축되고, 이후 어떤 수의
     * [ComicArchive.pageImageBytes]/[ComicArchive.coverImageBytes] 요청에도 항목을 다시 나열하거나
     * 다시 정렬하지 않고 응답할 수 있다.
     *
     * 공개된 [parse]/[pageImageBytes]/[coverImageBytes] 오버로드는 모두 이곳을 거쳐가므로, 단일
     * 응답만 필요한 호출자는 기존의 일회성 동작을 그대로 유지하고, 같은 책의 페이지를 계속 넘기는
     * 호출자 — [com.tedd.teddreader.core.data.repository.DocumentRepositoryImpl]의 페이지 윈도우
     * 경로 — 는 모든 윈도우 요청에 걸쳐 아카이브 하나를 열어 둔 채로 ZIP 인덱싱 비용을 단 한 번만
     * 치를 수 있다.
     *
     * @param path CBZ 파일의 위치. 임시 복사 없이 직접 연다; 호출자가 그 파일의 수명을 소유하며,
     *   반환된 아카이브를 사용하는 동안 디스크에 유지해야 한다.
     * @return [path]에 대한 [ComicArchive]. 페이지/표지 읽기를 반복해서 재사용할 수 있다.
     */
    internal open fun openArchive(path: Path): ComicArchive {
        val fileSystem = systemFileSystem().openZip(path)
        return ComicArchive(fileSystem = fileSystem, pagePaths = comicPagePaths(fileSystem))
    }

    /**
     * [bytes]를 고유한 이름의 임시 파일에 먼저 쏟아부어 ZIP으로 연 뒤 [block]을 실행한다 — 만화 바이트는
     * 메모리에 파일 전체로 도착하고, Okio의 ZIP 파일 시스템은 열기 위해 [Path]가 필요하기 때문이다 —
     * 그리고 [block]이 성공하든 던지든 그 스크래치 파일은 항상 이후에 삭제한다.
     *
     * @param bytes CBZ의 원시 콘텐츠.
     * @param block 반환되기 전에 열린 아카이브에서 필요한 것을 읽는다.
     * @return [block]이 반환하는 값.
     */
    private fun <T> withComicZip(bytes: ByteArray, block: (ComicArchive) -> T): T {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "tedd-reader-comic-${Random.nextLong().toString(16)}.cbz"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            block(openArchive(path))
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * 이미 디스크에 있는 [path]의 CBZ에 대해 임시 복사 없이 직접 열어 [block]을 실행한다.
     *
     * @param path CBZ 파일의 위치.
     * @param block 반환되기 전에 열린 아카이브에서 필요한 것을 읽는다.
     * @return [block]이 반환하는 값.
     */
    private fun <T> withComicZip(path: Path, block: (ComicArchive) -> T): T = block(openArchive(path))
}

/**
 * 반복 읽기를 위해 한 번만 열어 둔 CBZ. 이 아카이브의 Okio ZIP [FileSystem]과 자연 순서로 정렬된
 * 페이지 항목 [Path] 목록([ComicBookDocumentParser.sortedComicPageNames]가 정한 순서)은 한 번만
 * 계산되므로, 이후 모든 [pageImageBytes]/[coverImageBytes]/[pageCount] 호출은 아카이브를 다시
 * 나열하고 다시 정렬하는 대신 이를 재사용한다. 읽기 전용이며 자체 스크래치 파일을 갖지 않는다 —
 * ([ComicBookDocumentParser.openArchive]를 통해) 이를 만든 호출자가 하부 파일의 수명을 소유하며,
 * 이 아카이브가 사용되는 동안 디스크에 유지해야 한다.
 *
 * @property fileSystem 모든 페이지 읽기가 끌어오는, 열린 ZIP 파일 시스템.
 * @property pagePaths 이미 읽기 순서로 정렬된 아카이브의 페이지 항목들.
 */
internal class ComicArchive(
    private val fileSystem: FileSystem,
    private val pagePaths: List<Path>,
) {
    /** 이 아카이브가 담고 있는 페이지 수 — 읽기 순서로 정렬된 [pagePaths]의 크기. */
    val pageCount: Int get() = pagePaths.size

    /**
     * 이미 열려 있는 이 아카이브에서 [pageIndexes]에 해당하는 페이지들의 이미지 바이트를 읽는다. 이
     * 아카이브가 구축될 때 만들어진 페이지 인덱스를 재구축하지 않고 그대로 재사용한다.
     *
     * @param pageIndexes 읽기 순서 기준, 읽을 0부터 시작하는 페이지 번호들; 빈 집합이면 빈 맵으로
     *   바로 반환한다.
     * @return 페이지 인덱스를 키로 하는 바이트 맵. 실제로 존재했고 항목을 실제로 읽을 수 있었던
     *   인덱스만 포함한다 — 마지막 페이지를 넘어선 인덱스나, 손상되었거나 지나치게 크거나
     *   ([MaxComicPageBytes] 참고) 그 밖의 이유로 읽을 수 없는 페이지 항목은 전체 호출을 실패시키는
     *   대신 그냥 결과에서 빠진다.
     */
    fun pageImageBytes(pageIndexes: Set<Int>): Map<Int, ByteArray> {
        if (pageIndexes.isEmpty()) return emptyMap()
        return pageIndexes.sorted().mapNotNull { pageIndex ->
            pagePaths.getOrNull(pageIndex)
                ?.let { pagePath -> fileSystem.readComicPageOrNull(pagePath) }
                ?.let { pageBytes -> pageIndex to pageBytes }
        }.toMap()
    }

    /**
     * 첫 페이지의 이미지 바이트 — 이 포맷에서 표지를 대신하는 것.
     *
     * @return 첫 페이지의 바이트, 또는 아카이브의 인덱스 0에 페이지가 없거나 그 페이지를 읽을 수
     *   없었다면 null([pageImageBytes] 참고).
     */
    fun coverImageBytes(): ByteArray? = pageImageBytes(setOf(0))[0]
}

/**
 * [ComicBookDocumentParser.parse]의 두 오버로드가 [pageCount]를 알게 되면 공유하는 CBZ
 * [ReaderDocument] 생성 로직 — 만화에는 읽을 텍스트가 없으므로 섹션 목록은 항상 비어 있다.
 *
 * @param id 변경 없이 그대로 전달되는 원본 파일의 식별자.
 * @param title 문서에 표시될 레이블.
 * @param pageCount 아카이브에서 확인된 페이지 수; 양수여야 한다.
 * @return 섹션이 없는, [DocumentFormat.CBZ]의 [ReaderDocument].
 * @throws IllegalArgumentException [pageCount]가 양수가 아닐 때.
 */
internal fun comicReaderDocument(
    id: DocumentId,
    title: String,
    pageCount: Int,
): ReaderDocument {
    require(pageCount > 0) { "Comic pageCount must be positive." }
    return ReaderDocument(
        id = id,
        format = DocumentFormat.CBZ,
        title = title,
        sections = emptyList(),
        pageCount = pageCount,
    )
}

/**
 * [names] 중 실제 만화 페이지인 것들을, 리더가 보여줘야 할 순서로 정렬한 것: `cover`라는 이름을 가진
 * 항목(확장자 무관)은 자연 순서상 어디에 위치하든 항상 맨 앞에 오고, 그 다음 나머지 페이지들은 자연스러운
 * 숫자 순서로 온다 — 그래서 일반 문자열 정렬이 배치할 위치가 아니라, 사람이 파일명을 읽었을 때 기대하는
 * 대로 `page2`가 `page10`보다 앞에 온다.
 *
 * 필터링 전에 백슬래시 구분자는 `/`로 정규화되고 선행 `/`는 제거되므로, Windows에서 만들어진
 * 아카이브의 항목 목록에서 그대로 가져온 이름도 [isComicPageName]과 여전히 일치한다.
 *
 * @param names 아카이브에 저장된 그대로의 원시 항목 이름(경로)들.
 * @return [names] 중 페이지 항목들을 위에서 설명한 순서로 재정렬한 것; [isComicPageName]이 거부하는
 *   것들 — 메타데이터 폴더, 리소스 포크 파일, 지원되지 않는 확장자 — 은 전부 제외된다.
 */
internal fun sortedComicPageNames(names: List<String>): List<String> = names
    .asSequence()
    .map { it.replace('\\', '/').removePrefix("/") }
    .filter(::isComicPageName)
    .sortedWith(Comparator { left, right ->
        val coverOrder = isCoverPageName(right).compareTo(isCoverPageName(left))
        if (coverOrder != 0) coverOrder else compareNaturalPageNames(left, right)
    })
    .toList()

/**
 * [zip] 안에서 [isComicPageName]이 받아들이는 모든 항목을 그 [Path]로 해석하고,
 * [sortedComicPageNames]로 읽기 순서에 맞게 정렬한 것.
 */
private fun comicPagePaths(zip: FileSystem): List<Path> {
    val pathsByName = zip.listRecursively("/".toPath())
        .associateBy { path -> path.toString().removePrefix("/") }
    return sortedComicPageNames(pathsByName.keys.toList()).mapNotNull(pathsByName::get)
}

/**
 * [name]이 [sortedComicPageNames]가 페이지로 취급해야 할 항목인지 여부: 확장자가 [ComicPageExtensions]
 * 중 하나여야 하고, Mac에서 만든 ZIP이 흔히 추가하는 두 종류의 비-페이지 잡동사니 중 하나가 아니어야
 * 한다 — `__MACOSX/` 메타데이터 폴더 아래의 항목, 또는 이름이 `._`로 시작하는 AppleDouble 리소스
 * 포크 파일.
 *
 * @param name [sortedComicPageNames]가 이미 슬래시로 정규화한 원시 항목 이름.
 */
private fun isComicPageName(name: String): Boolean {
    val normalized = name.lowercase()
    val fileName = normalized.substringAfterLast('/')
    if (normalized.startsWith("__macosx/") || "/__macosx/" in normalized || fileName.startsWith("._")) return false
    return normalized.substringAfterLast('.', missingDelimiterValue = "") in ComicPageExtensions
}

/**
 * [name]의 파일명이 확장자를 제외하고 정확히 `cover`인지 여부 — [sortedComicPageNames]가 자연 순서를
 * 무시하고 항상 맨 앞에 두는 그 이름.
 *
 * @param name 원시 항목 이름.
 */
private fun isCoverPageName(name: String): Boolean =
    name.substringAfterLast('/').substringBeforeLast('.').equals("cover", ignoreCase = true)

/**
 * [left]와 [right]의 자연 순서 비교: 연속된 숫자 구간은 숫자로 비교되고(선행 0 제거, 같은 위치에서는
 * 더 긴 숫자 구간이 항상 더 짧은 구간을 이긴다) `page2`가 `page10`보다 앞에 오게 되며, 숫자가 아닌
 * 문자의 연속 구간은 일반 소문자 텍스트로 비교된다. 모든 문자 위치가 동률일 때만 전체 길이 비교,
 * 그다음 원본 문자열 비교로 대체한다.
 *
 * @param left 비교되는 한쪽 항목 이름; 대소문자는 무시된다(비교 전 소문자화됨).
 * @param right 비교되는 다른 쪽 항목 이름; 같은 방식으로 대소문자가 무시된다.
 * @return [left]가 먼저 오면 음수, [right]가 먼저 오면 양수, 동등하면 0.
 */
private fun compareNaturalPageNames(left: String, right: String): Int {
    val a = left.lowercase()
    val b = right.lowercase()
    var aIndex = 0
    var bIndex = 0
    while (aIndex < a.length && bIndex < b.length) {
        val aIsDigit = a[aIndex].isDigit()
        val bIsDigit = b[bIndex].isDigit()
        if (aIsDigit && bIsDigit) {
            val aEnd = a.indexAfterRun(aIndex, Char::isDigit)
            val bEnd = b.indexAfterRun(bIndex, Char::isDigit)
            val aNumber = a.substring(aIndex, aEnd).trimStart('0').ifEmpty { "0" }
            val bNumber = b.substring(bIndex, bEnd).trimStart('0').ifEmpty { "0" }
            val lengthOrder = aNumber.length.compareTo(bNumber.length)
            if (lengthOrder != 0) return lengthOrder
            val numberOrder = aNumber.compareTo(bNumber)
            if (numberOrder != 0) return numberOrder
            aIndex = aEnd
            bIndex = bEnd
        } else {
            val characterOrder = a[aIndex].compareTo(b[bIndex])
            if (characterOrder != 0) return characterOrder
            aIndex += 1
            bIndex += 1
        }
    }
    return a.length.compareTo(b.length).takeIf { it != 0 } ?: left.compareTo(right)
}

/**
 * [start] 이후 위치 중 문자가 [predicate]를 만족하지 못하는 첫 위치의 인덱스 — 숫자 구간이 어디서
 * 끝나는지 찾기 위해 [compareNaturalPageNames]가 사용한다.
 *
 * @receiver 스캔 대상 문자열.
 * @param start 스캔을 시작할 인덱스; 그 자체는 [predicate]를 만족해야 한다.
 * @param predicate 구간의 문자들이 계속 만족해야 하는 조건.
 * @return 구간 끝 바로 다음 인덱스, 또는 구간이 리시버의 끝까지 이어진다면 [String.length].
 */
private fun String.indexAfterRun(start: Int, predicate: (Char) -> Boolean): Int {
    var index = start
    while (index < length && predicate(this[index])) index += 1
    return index
}

/**
 * 페이지 항목 하나의 전체 바이트를 읽되, 손상되었거나 예상외로 거대한 단일 항목이 페이지 이미지가
 * 필요로 해야 할 메모리를 초과하지 않도록 [MaxComicPageBytes]로 상한을 둔다.
 *
 * @receiver 읽어올 아카이브.
 * @param path [comicPagePaths]가 해석한 페이지의 항목 경로.
 * @return 페이지의 바이트, 또는 항목을 열 수 없었거나, 읽는 중 예외가 발생했거나, 크기가
 *   [MaxComicPageBytes]를 초과했다면 null.
 */
private fun FileSystem.readComicPageOrNull(path: Path): ByteArray? {
    val source = runCatching { source(path).buffer() }.getOrNull() ?: return null
    return try {
        val buffer = Buffer()
        var totalBytes = 0L
        while (true) {
            val read = source.read(buffer, 8_192)
            if (read == -1L) break
            totalBytes += read
            if (totalBytes > MaxComicPageBytes) return null
        }
        buffer.readByteArray()
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    }
}

/** [isComicPageName]이 페이지 이미지로 받아들이는 파일 확장자들. */
private val ComicPageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** [readComicPageOrNull]이 강제하는, 페이지 하나의 디코딩된 크기 상한. */
private const val MaxComicPageBytes = 16L * 1024 * 1024
