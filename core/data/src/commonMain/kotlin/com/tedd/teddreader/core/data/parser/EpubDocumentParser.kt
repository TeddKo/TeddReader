package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentFormat
import com.tedd.teddreader.core.common.model.DocumentId
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderDocument
import com.tedd.teddreader.core.common.model.ReaderNavigation
import com.tedd.teddreader.core.common.model.ReaderNavigationItem
import com.tedd.teddreader.core.common.model.ReaderSection
import com.tedd.teddreader.core.common.model.TextRange
import com.tedd.teddreader.core.common.model.isBlankIgnoringObjects
import kotlin.random.Random
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import org.koin.core.annotation.Single

/**
 * 한 챕터의 원본 XHTML. 실제 EPUB의 스파인에서 읽어온 것일 수도 있고, 파싱할 실제 EPUB
 * 컨테이너가 없는 [EpubDocumentParser.parseChapters] 호출자가 직접 넘긴 것일 수도 있다.
 */
data class EpubChapter(
    /** 챕터 제목; null이면 [EpubDocumentParser.parseChapters]에서 `"Chapter N"`(1부터 시작)으로 대체된다. */
    val title: String?,
    /** 챕터의 원본 XHTML 마크업. [parseXhtmlContent]로 파싱된다. */
    val xhtml: String,
    /** 컨테이너 내에서 챕터가 위치한 곳. 상대 이미지 참조를 해석하는 데 쓰인다. */
    val path: String? = null,
)

/**
 * [EpubDocumentParser.parseWithCover]가 찾아낸 것: 문서 자체와, 파서가 페이지에 커버 크기를
 * 맞추기 위해 이미 디코딩해 뒀다가([fillIntrinsicImageSizes] 참고) 그렇지 않으면 버렸을 커버
 * 이미지 바이트. 문서를 곧 영속화할 호출자가 나중에 책 전체를 두 번째로 열지 않고도 커버를
 * 디스크에 캐시할 수 있도록 보관해 둔다(DocumentRepositoryImpl.importDocument 참고).
 */
data class EpubParseResult(
    /** 파싱된 문서. */
    val document: ReaderDocument,
    /**
     * 책이 커버를 선언하고 디코딩에 성공했다면 그 커버 이미지의 바이트; [EpubDocumentParser.parseWithCover] 참고.
     */
    val coverBytes: ByteArray?,
)

/**
 * EPUB(OCF/OPF 패키징 스펙을 따르는 ZIP 컨테이너)를 [ReaderDocument]로 파싱한다: 읽을 수 있는
 * 챕터마다 평탄화되어 페이지 매김 가능한 텍스트의 [ReaderSection] 하나, 그리고 그 안의 범위를
 * 가리키는 [ReaderBlock](이미지, 헤딩, 표, …)들. 커버 추출, 임베드 이미지 추출, 그리고 컨테이너
 * 파싱을 완전히 우회하는 호출자-공급-챕터 진입점([parseChapters])이 모두 여기, 메인
 * [parseWithCover]/[parse] 쌍과 함께 있다.
 *
 * 책이 커버를 선언한 경우, 커버는 실제 챕터가 하나라도 파싱되기 전에 항상 섹션 0으로 합성된다.
 * 스파인을 훑는 도중에 발견하는 대신 먼저 확정해 두는 것이 다른 모든 섹션의 오프셋을 안정적으로
 * 유지하는 방법이기 때문이다; 더 나중에 처리한다면 이미 나눠준 모든 오프셋을 밀어서 자리를
 * 만들어야 한다. 전체 파싱 흐름은 [parseWithCover]를, 한 챕터 자체의 마크업과 스타일시트가
 * 텍스트가 되는 과정은 [EpubXhtmlParser]/[EpubCssEngine]을 참고.
 */
@Single
open class EpubDocumentParser {
    /**
     * [bytes]의 EPUB를 [ReaderDocument]로 파싱한다. [parseWithCover]가 도중에 함께 디코딩하는 커버
     * 바이트는 버린다. 호출자가 문서를 곧 영속화할 예정이라면, 나중에 커버를 얻으려고 파일을 다시
     * 여는 일을 피하기 위해 대신 [parseWithCover]를 쓰는 게 좋다.
     *
     * @param id 원본 파일의 식별자. 그대로 전달된다.
     * @param title 폴백 제목. EPUB 자체의 `dc:title` 메타데이터가 없을 때만 쓰인다.
     * @param bytes EPUB의 원본 내용(ZIP 아카이브).
     * @return 파싱된 [ReaderDocument].
     */
    fun parse(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): ReaderDocument = parseWithCover(id = id, title = title, bytes = bytes).document

    /**
     * [path]에 이미 디스크에 있는 EPUB를 [ReaderDocument]로 파싱한다. [parseWithCover]가 함께
     * 디코딩하는 커버 바이트는 버린다. 언제 대신 [parseWithCover]를 써야 하는지는 [parse]의
     * `bytes` 오버로드를 참고.
     *
     * @param id 원본 파일의 식별자. 그대로 전달된다.
     * @param title 폴백 제목. EPUB 자체의 `dc:title` 메타데이터가 없을 때만 쓰인다.
     * @param path EPUB 파일의 위치. 임시 복사본 없이 ZIP으로 직접 열린다.
     * @param fileSystem [path]를 읽을 파일 시스템; 기본값은 실제 플랫폼 파일 시스템이며, 호출자
     *   (또는 테스트)가 대체 구현을 넘길 수 있도록 오버라이드 가능하다.
     * @return 파싱된 [ReaderDocument].
     */
    fun parse(
        id: DocumentId,
        title: String,
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): ReaderDocument = parseWithCover(id = id, title = title, path = path, fileSystem = fileSystem).document

    /**
     * [parse]와 같지만, 파서가 도중에 디코딩한 커버 바이트도 함께 반환한다 — [EpubParseResult] 참고.
     *
     * [bytes]는 고유한 이름의 임시 파일로 흘려보내져, 나머지 파싱 과정이 [path] 오버로드와 같은
     * 방식으로 [okio.FileSystem.openZip]을 통해 동작할 수 있게 한다; 파싱이 성공하든 예외를
     * 던지든 이 스크래치 파일은 이후 항상 삭제된다. 파일 이름은 [id]가 아니라 무작위 토큰이다:
     * 문서의 소스 URI를 따서 이름을 지었더니 한 번 파일시스템이 경로 한 구성요소에 허용하는
     * 255바이트 제한을 넘겨버려, EPUB 폴더를 임포트할 때 모든 파일이 `ENAMETOOLONG`으로
     * 실패했었다. 이름은 파일이 존재하는 동안만 고유하면 되고, 무작위 토큰은 그 위험 없이 이미
     * 이를 보장한다.
     *
     * @param id 원본 파일의 식별자. 그대로 전달된다.
     * @param title 폴백 제목. EPUB 자체의 `dc:title` 메타데이터가 없을 때만 쓰인다.
     * @param bytes EPUB의 원본 내용.
     * @return 파싱된 문서와, 있다면 그 커버 바이트.
     */
    fun parseWithCover(
        id: DocumentId,
        title: String,
        bytes: ByteArray,
    ): EpubParseResult {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-reader-epub-${Random.nextLong().toString(16)}.epub"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            parseWithCover(id = id, title = title, path = path, fileSystem = fileSystem)
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * [parse]와 같지만, 파서가 도중에 디코딩한 커버 바이트도 함께 반환한다 — [EpubParseResult]
     * 참고. EPUB가 실제로 파싱되는 곳이 여기다; [parseWithCover]의 `bytes` 오버로드는 단지 입력을
     * 먼저 임시 파일로 흘려보낸 뒤 여기로 위임할 뿐이다.
     *
     * 컨테이너의 `META-INF/container.xml`을 먼저 읽어 OPF(패키지 문서) 경로를 찾는다. EPUB에
     * OPF가 전혀 없으면 — 손상됐거나 실제로는 EPUB가 아니거나 — 더 나은 방법으로 처리할 패키지
     * 메타데이터가 없으므로, 아카이브 안의 모든 `.xhtml`/`.html` 엔트리를 [parseChapters]를 통해
     * 각각 자신만의 챕터로 취급하는 폴백으로 넘어간다. 이 경우 커버도, 내비게이션도 없고, 각
     * 챕터는 자기 파일명으로 이름 붙는다. OPF가 발견되면 그 패키지 데이터(스파인, 매니페스트,
     * 커버, 제목, 내비게이션 문서 또는 NCX 경로)가 [parsePackageData]로 한 번 파싱되어 아래
     * 모든 과정을 주도한다.
     *
     * OPF가 커버 이미지를 지정하고 그 바이트를 읽을 수 있다면, 어떤 챕터보다도 먼저 한 글자짜리
     * 합성 [ReaderBlockKind.COVER_IMAGE] 섹션이 방출된다 — 왜 이 작업이 다른 오프셋이 배정되기
     * 전에 이루어져야 하는지는 이 클래스 자체의 문서를 참고. *linear*인 스파인 항목만 챕터가
     * 된다; 스파인 항목의 `linear="no"`는 그것이 정상적인 읽기 순서에 속하지 않는다는(광고
     * 페이지, 대체 포맷 중복본 등) 책 자체의 지시이며, 완전히 건너뛰어 섹션이 되지 않는다.
     * 커버 섹션이 방출됐고 첫 번째 linear 스파인 항목이 알고 보니 바로 그 커버 그림 외에
     * 아무것도 아닌 경우([isPureCoverXhtml]), 그 항목도 건너뛴다 — 그 내용은 이미 합성 섹션이
     * 보여주고 있다 — 하지만 그 자신의 경로는 여전히 커버 섹션에 대해 기록되어, 그 챕터 파일을
     * 대상으로 하는 내비게이션 항목이 아무 데도 아닌 커버로 해석되도록 한다. 남은 각 스파인
     * 항목은 [parseXhtmlContent]로 파싱되며, 이미지 참조는 컨테이너 내 자신의 위치를 기준으로
     * 해석되고, 챕터의 CSS 캐스케이드([linkedCss])가 전달된다 — 스타일시트 집합별로 캐시되는데,
     * 책은 보통 수백 개 챕터에 걸쳐 같은 몇 안 되는 시트를 재사용하기 때문이다. 섹션 제목은
     * 헤딩 이미지의 title 속성을 먼저 시도하고([XhtmlContent.headingTitle]), 그다음 챕터 자체의
     * 첫 헤딩 텍스트([firstHeadingTitle]), 그다음 매니페스트 항목의 `title`, 마지막으로 그
     * 무엇도 챕터 이름을 지어주지 않을 때 최후 수단으로 원본 `id`를 사용해 해석된다. XHTML을
     * 읽을 수 없는 스파인 항목(누락되거나 읽을 수 없는 엔트리)은 전체 파싱을 실패시키지 않고
     * 건너뛴다.
     *
     * 모든 섹션이 파악되면, 파싱 중 기록된 섹션 경로와 앵커 오프셋을 기준으로 내비게이션
     * ([resolveNavigation])이 해석되고, 오프셋 0을 대상으로 하는 nav 항목이 있는 섹션은 그 항목
     * 자체의 라벨로 제목이 다시 붙는다 — 이로써 책 자체의 목차가, 헤딩이나 매니페스트에서 유도된
     * 제목과 다를 경우 그것을 덮어쓸 수 있게 한다. 마지막으로 [fillIntrinsicImageSizes]가 모든
     * 이미지 블록에 그림 자신의 바이트에서 읽은 종횡비(그리고 다른 어떤 것도 선언하지 않았을 때는
     * 자연 픽셀 너비)를 채워 넣는다: 커버의 바이트는 위에서 이미 디코딩된 것을 재사용하며 두
     * 번째로 읽지 않고, 그 외 모든 이미지의 헤더는 아카이브에서 새로 읽는다.
     *
     * @param id 원본 파일의 식별자. 그대로 전달된다.
     * @param title 폴백 제목. EPUB 자체의 `dc:title` 메타데이터가 없을 때만 쓰인다.
     * @param path EPUB 파일의 위치.
     * @param fileSystem [path]를 읽을 파일 시스템; 기본값은 실제 플랫폼 파일 시스템.
     * @return 파싱된 문서와, 있다면 그 커버 바이트.
     */
    fun parseWithCover(
        id: DocumentId,
        title: String,
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): EpubParseResult {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull(ContainerPath.toPath())?.let(::findRootFilePath)
        if (opfPath == null) {
            val fallbackChapters = zip.listRecursively("/".toPath())
                .filter { it.name.endsWith(".xhtml") || it.name.endsWith(".html") }
                .mapNotNull { candidate ->
                    zip.readUtf8OrNull(candidate)?.let { xhtml ->
                        EpubChapter(title = candidate.name, xhtml = xhtml, path = candidate.toString())
                    }
                }
                .toList()
            return EpubParseResult(document = parseChapters(id, title, fallbackChapters), coverBytes = null)
        }

        val opf = zip.readUtf8OrNull(opfPath).orEmpty()
        val packageData = parsePackageData(opf = opf, opfPath = opfPath)
        val documentTitle = packageData.documentTitle ?: title
        val coverHref = packageData.coverHref
        val coverBytes = coverHref?.let { zip.readBytesOrNull(it.toPath()) }
        val parsedNavigation = packageData.navigationItemPath?.let { navPath ->
            zip.readUtf8OrNull(navPath.toPath())?.let(::parseEpubNavDocument)
        } ?: packageData.ncxPath?.let { ncxPath ->
            zip.readUtf8OrNull(ncxPath.toPath())?.let(::parseNcxDocument)
        } ?: ParsedNavigation()

        val sections = mutableListOf<ReaderSection>()
        val blocks = mutableListOf<ReaderBlock>()
        val sectionStartOffsets = linkedMapOf<Int, Long>()
        val sectionAnchorOffsets = linkedMapOf<Int, Map<String, Long>>()
        val sectionPathByIndex = linkedMapOf<Int, String>()
        var nextOffset = 0L
        var nextIndex = 0
        var coverSectionIndex: Int? = null

        if (coverHref != null && coverBytes != null) {
            val coverRange = TextRange(nextOffset, nextOffset + 1)
            sections += ReaderSection(
                index = nextIndex,
                text = " ",
                range = coverRange,
                title = documentTitle,
            )
            blocks += ReaderBlock(
                kind = ReaderBlockKind.COVER_IMAGE,
                range = coverRange,
                imageHref = coverHref,
                label = documentTitle,
            )
            sectionStartOffsets[nextIndex] = coverRange.start
            sectionPathByIndex[nextIndex] = coverHref
            coverSectionIndex = nextIndex
            nextIndex += 1
            nextOffset = coverRange.end + SectionSeparatorLength
        }

        val cssCache = mutableMapOf<String, EpubCss>()
        packageData.spineItems.filter { it.linear }.forEachIndexed { spineOrder, spineItem ->
            val xhtml = zip.readUtf8OrNull(spineItem.path.toPath()) ?: return@forEachIndexed
            val content = parseXhtmlContent(
                xhtml = xhtml,
                baseOffset = nextOffset,
                resolveImageHref = { source -> resolveContainerHref(spineItem.path, source) },
                css = linkedCss(xhtml, spineItem.path, zip, cssCache),
            )
            if (coverHref != null && spineOrder == 0 && isPureCoverXhtml(content, coverHref)) {
                if (coverSectionIndex != null) sectionPathByIndex[coverSectionIndex] = spineItem.path
                return@forEachIndexed
            }
            val sectionTitle = content.headingTitle
                ?: firstHeadingTitle(content, nextOffset)
                ?: spineItem.item.title
                ?: spineItem.item.id
            val range = TextRange(nextOffset, nextOffset + content.text.length)
            val sectionIndex = nextIndex
            sections += ReaderSection(
                index = sectionIndex,
                text = content.text,
                range = range,
                title = sectionTitle,
            )
            blocks += content.blocks
            sectionStartOffsets[sectionIndex] = range.start
            sectionAnchorOffsets[sectionIndex] = content.anchors
            sectionPathByIndex[sectionIndex] = spineItem.path
            nextIndex += 1
            nextOffset = range.end + SectionSeparatorLength
        }

        val firstReadableContentSectionIndex = sections.firstOrNull { section ->
            section.index != coverSectionIndex && section.text.isNotBlank()
        }?.index
        val navigation = resolveNavigation(
            navigation = parsedNavigation,
            sectionPathByIndex = sectionPathByIndex,
            sectionStartOffsets = sectionStartOffsets,
            sectionAnchorOffsets = sectionAnchorOffsets,
            coverSpineIndex = coverSectionIndex,
            firstReadableContentSectionIndex = firstReadableContentSectionIndex,
            navigationBasePath = packageData.navigationItemPath ?: packageData.ncxPath ?: opfPath.toString(),
        )
        val navigationTitlesBySection = navigation.items
            .filter { it.offset == 0L }
            .associateBy({ it.spineIndex }, { it.title })
        val retitledSections = sections.map { section ->
            section.copy(title = navigationTitlesBySection[section.index] ?: section.title)
        }

        fillIntrinsicImageSizes(blocks, zip, coverHref, coverBytes)

        return EpubParseResult(
            document = ReaderDocument(
                id = id,
                format = DocumentFormat.EPUB,
                title = documentTitle,
                sections = retitledSections,
                blocks = blocks,
                navigation = navigation,
            ),
            coverBytes = coverBytes,
        )
    }

    /**
     * 책의 커버 이미지 바이트. [bytes]에서 커버를 찾아 추출하는 데 필요한 만큼만 — 책 전체가 아니라
     * OPF와 커버 엔트리 자체만 — 다시 읽어 디코딩한다.
     *
     * @param bytes EPUB의 원본 내용. ZIP으로 열 수 있도록 임시 파일로 흘려보낸다
     *   ([parseWithCover]의 `bytes` 오버로드와 같은 이유).
     * @return 커버 이미지의 원본 바이트, 또는 EPUB가 커버를 선언하지 않았거나, OPF가 없거나,
     *   선언된 커버 엔트리를 읽을 수 없으면 null.
     */
    fun coverImageBytes(bytes: ByteArray): ByteArray? {
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-reader-epub-cover-${Random.nextLong().toString(16)}.epub"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            coverImageBytes(path, fileSystem)
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * [bytes]에 담긴 EPUB 안에 존재하는 [hrefs] 중 어느 엔트리든 그 원본 바이트를 읽는다. 책의
     * 나머지 부분은 전혀 파싱하지 않는다.
     *
     * @param bytes EPUB의 원본 내용. ZIP으로 열 수 있도록 임시 파일로 흘려보낸다.
     * @param hrefs 읽어들일 컨테이너 상대 경로들; 빈 항목은 버려지며, 그렇게 걸러낸 뒤 집합이
     *   비게 되면 아카이브를 아예 열지 않고 단락 처리한다.
     * @return 그것을 만들어낸(트리밍된) href를 키로 하는 바이트들; 일치하는 엔트리가 없거나 그
     *   엔트리를 읽을 수 없는 href는 결과에서 그냥 빠진다.
     */
    fun extractEmbeddedImageBytes(
        bytes: ByteArray,
        hrefs: Set<String>,
    ): Map<String, ByteArray> {
        if (hrefs.isEmpty()) return emptyMap()
        val normalizedHrefs = hrefs.map(String::trim).filter(String::isNotEmpty).toSet()
        if (normalizedHrefs.isEmpty()) return emptyMap()
        val fileSystem = systemFileSystem()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "tedd-reader-epub-embedded-${Random.nextLong().toString(16)}.epub"
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
        return try {
            extractEmbeddedImageBytes(
                path = path,
                hrefs = normalizedHrefs,
                fileSystem = fileSystem,
            )
        } finally {
            fileSystem.delete(path)
        }
    }

    /**
     * 호출자가 넘긴 [chapters] 목록으로부터 컨테이너/OPF 처리 과정을 완전히 우회해 곧바로
     * [ReaderDocument]를 만든다 — [parseWithCover]의 OPF-없음 폴백과, 실제 EPUB 컨테이너가
     * 아닌 다른 어딘가에서 챕터 XHTML을 이미 가지고 있는 호출자 양쪽에서 쓰인다.
     *
     * 여기엔 스타일시트나 CSS 캐스케이드가 없다 — 각 챕터는 [parseXhtmlContent] 자체의
     * 기본값으로 파싱된다 — 그리고 빈 [ReaderNavigation] 외의 내비게이션도 없다. 챕터를 직접
     * 조립하는 호출자에겐 해석할 OPF 유래 목차가 없기 때문이다. 제목이 없는 챕터는 `"Chapter
     * N"`(1부터 시작)으로 대체된다. 섹션들은 문서 하나로 읽힐 때 개행 문자 하나로 이어붙여지므로,
     * 그 구분자는 각 챕터 자신의 오프셋 일부로 계산돼야 한다; 이를 빠뜨리면 이후 각 챕터의
     * 범위가 이미 지나온 챕터 수만큼 한 글자씩 밀려, 책 깊숙이 들어갈수록 읽기 위치와 검색
     * 결과가 잘못된 챕터를 가리키게 됐다.
     *
     * @param id 원본 파일의 식별자. 그대로 전달된다.
     * @param title 문서 전체의 라벨.
     * @param chapters 파싱할 챕터들. 읽기 순서대로.
     * @return 챕터당 섹션 하나, 내비게이션 없음으로 구성된 [DocumentFormat.EPUB] 형식의 [ReaderDocument].
     */
    fun parseChapters(
        id: DocumentId,
        title: String,
        chapters: List<EpubChapter>,
    ): ReaderDocument {
        var offset = 0L
        val sections = mutableListOf<ReaderSection>()
        val blocks = mutableListOf<ReaderBlock>()

        chapters.forEachIndexed { index, chapter ->
            val content = parseXhtmlContent(
                xhtml = chapter.xhtml,
                baseOffset = offset,
                resolveImageHref = { source -> resolveContainerHref(chapter.path, source) },
            )
            sections += ReaderSection(
                index = index,
                title = chapter.title ?: "Chapter ${index + 1}",
                text = content.text,
                range = TextRange(offset, offset + content.text.length),
            )
            blocks += content.blocks
            offset += content.text.length + SectionSeparatorLength
        }
        return ReaderDocument(
            id = id,
            format = DocumentFormat.EPUB,
            title = title,
            sections = sections.toList(),
            blocks = blocks.toList(),
            navigation = ReaderNavigation(),
        )
    }

    /**
     * 이미 [path]의 디스크에 있는 EPUB에서 직접 읽어낸 책의 커버 이미지 바이트.
     *
     * 호출자가 파일을 직접 디스크로 스트리밍할 수 있는 경우엔 `bytes` 오버로드보다 이쪽이
     * 낫다: 그 오버로드는 ZIP으로 열기도 전에 책 전체를 메모리에 들고 있어야 하므로, 수백
     * 메가바이트짜리 삽화가 있는 책은 그림 한 장에 도달하려고 전체 크기만큼 힙을 소모했다.
     * 여기서는 아카이브에서 커버 엔트리만 읽는다.
     *
     * @param path EPUB 파일의 위치.
     * @param fileSystem [path]를 읽을 파일 시스템; 기본값은 실제 플랫폼 파일 시스템.
     * @return 커버 이미지의 원본 바이트, 또는 EPUB가 커버를 선언하지 않았거나, OPF가 없거나,
     *   선언된 커버 엔트리를 읽을 수 없으면 null.
     */
    fun coverImageBytes(
        path: Path,
        fileSystem: FileSystem = systemFileSystem(),
    ): ByteArray? {
        val zip = fileSystem.openZip(path)
        val opfPath = zip.readUtf8OrNull(ContainerPath.toPath())?.let(::findRootFilePath) ?: return null
        val opf = zip.readUtf8OrNull(opfPath) ?: return null
        val coverPath = findEpubCoverHref(opf, opfPath) ?: return null
        return zip.readBytesOrNull(coverPath.toPath())
    }

    /**
     * 책 전체를 다시 읽지 않고, 이미 압축 해제된 사본에서 이미지를 바로 뽑아낸다.
     *
     * @param path EPUB 파일의 위치.
     * @param hrefs 읽어들일 컨테이너 상대 경로들.
     * @param fileSystem [path]를 읽을 파일 시스템; 기본값은 실제 플랫폼 파일 시스템.
     * @return 그것을 만들어낸 href를 키로 하는 바이트들; 일치하는 엔트리가 없거나 그 엔트리를
     *   읽을 수 없는 href는 결과에서 그냥 빠진다.
     */
    internal open fun extractEmbeddedImageBytes(
        path: Path,
        hrefs: Set<String>,
        fileSystem: FileSystem = systemFileSystem(),
    ): Map<String, ByteArray> {
        val zip = fileSystem.openZip(path)
        return hrefs.mapNotNull { href -> zip.readBytesOrNull(href.toPath())?.let { href to it } }.toMap()
    }
}

/**
 * 점진적 EPUB 임포트가 스파인 항목 하나를 스스로 파싱하기 전에 필요로 하는 모든 것 — OPF 자체의
 * 결정 사항들로, 한 번 확정되고(클래스 문서 참고: 커버를 나중에 확정하면 그 뒤의 모든 오프셋이
 * 밀린다) 이후 배치에서 다시 다뤄지지 않는다. [openEpubImportContainer]는 EPUB에 OPF가 전혀
 * 없을 때만 null을 반환한다 — 이는 [EpubDocumentParser.parseWithCover]의 기존 폴백-챕터 경로가
 * 이미 단일, 비-점진적 파싱으로 처리하는 유일한 경우다. 스트리밍할 스파인이 없기 때문이다.
 */
internal class EpubImportContainer(
    /**
     * 열린 아카이브; 임포트 전체에 걸쳐 모든 스파인 항목과 스타일시트가 이것으로부터 다시 읽힌다.
     */
    val zip: FileSystem,
    /** 컨테이너 내 OPF 패키지 문서의 경로. */
    val opfPath: Path,
    /** 책의 제목: 자체 `dc:title`, 또는 그것이 없을 때 호출자가 제공한 폴백. */
    val documentTitle: String,
    /** OPF의 파싱된 매니페스트, 스파인, 내비게이션/NCX 경로, 커버 href. */
    val packageData: PackageData,
    /**
     * 스파인 항목이 파싱되기 전에 커버에 대해 확정된 내용; [resolveEpubCoverDecision] 참고.
     */
    val coverDecision: EpubCoverDecision,
) {
    /**
     * [PackageData.spineItems]를 linear한 것들로 좁힌 것 — 섹션이 될 수 있는 유일한 스파인
     * 항목들.
     */
    val linearSpineItems: List<SpineItem> = packageData.spineItems.filter { it.linear }
    /** 점진적 스파인 파싱 전반에서 공유되어, 같은 연결된 CSS 캐스케이드가 임포트당 한 번만 파싱되게 한다. */
    val linkedCssCache = mutableMapOf<String, EpubCss>()
}

/** [openEpubImportContainer]가 실제로 어떤 스파인 항목이든 파싱되기 전에 커버에 대해 확정하는 내용. */
internal data class EpubCoverDecision(
    /** 커버의 컨테이너 상대 경로, 또는 OPF가 커버를 선언하지 않았으면 null. */
    val coverHref: String?,
    /** 커버 이미지의 원본 바이트, 또는 커버가 없거나 그 엔트리를 읽을 수 없으면 null. */
    val coverBytes: ByteArray?,
    /** [coverHref]와 [coverBytes]가 둘 다 non-null일 때 true — 실제 커버 섹션이 표시될 것이다. */
    val hasCoverSection: Boolean,
    /** 첫 번째 linear 스파인 항목이 그 자신의 합성 섹션이 이미 보여주는 커버 그림 외에
     * 아무것도 아닐 때 true — 그 항목은 스파인 루프에 의해 건너뛰어져 자신만의 섹션이 되지
     * 않는다([EpubDocumentParser.parseWithCover] 내부의 `isPureCoverXhtml` 검사를 그대로
     * 반영). */
    val spineOrder0Skipped: Boolean,
)

/** 새로 파싱된 섹션 하나와 그 블록들 — [DocumentRepositoryImpl.importNextSections]
 * (core:data 리포지토리)가 점진적 임포트의 각 단계마다 덧붙이는 단위. */
internal data class EpubParsedSection(
    /** 섹션 자체. */
    val section: ReaderSection,
    /** 범위가 [section] 안에 들어가는 블록들. */
    val blocks: List<ReaderBlock>,
)

/**
 * [zip]을 점진적-임포트 컨테이너로 연다: OPF를 한 번 읽어 커버에 대한 모든 것을 미리 확정해
 * 두므로, 이후 각 스파인 항목을 [parseEpubSpineItem]으로 이것들을 다시 유도하지 않고도 스스로
 * 파싱할 수 있다.
 *
 * @param zip 이미 열려 있는 EPUB 아카이브.
 * @param title OPF에 `dc:title`이 없을 때만 쓰이는 폴백 문서 제목.
 * @return 열린 컨테이너, 또는 [zip]에 OPF를 가리키는 `META-INF/container.xml`이 없으면 null —
 *   점진적 임포트가 전혀 진행할 수 없는 유일한 경우다, 스트리밍할 스파인이 없기 때문이다; 호출자는
 *   대신 [EpubDocumentParser.parseChapters]의 단발성, 비-점진적 경로로 폴백한다.
 */
internal fun openEpubImportContainer(zip: FileSystem, title: String): EpubImportContainer? {
    val opfPath = zip.readUtf8OrNull(ContainerPath.toPath())?.let(::findRootFilePath) ?: return null
    val opf = zip.readUtf8OrNull(opfPath).orEmpty()
    val packageData = parsePackageData(opf = opf, opfPath = opfPath)
    return EpubImportContainer(
        zip = zip,
        opfPath = opfPath,
        documentTitle = packageData.documentTitle ?: title,
        packageData = packageData,
        coverDecision = resolveEpubCoverDecision(zip, packageData),
    )
}

/**
 * [packageData]의 책에 대한 [EpubCoverDecision]을 확정한다: 커버가 있는지, 그 바이트가
 * 무엇인지, 그리고 첫 번째 linear 스파인 항목이 바로 그 커버 그림 외에 아무것도 아닌지(이
 * 경우 스파인 루프는 커버를 두 번 보여주는 대신 그것을 건너뛰어야 한다).
 *
 * @param zip 이미 열려 있는 EPUB 아카이브. 커버 엔트리와, 필요하다면 첫 스파인 항목의 XHTML을
 *   읽는 데 쓰인다.
 * @param packageData OPF의 파싱된 매니페스트, 스파인, 커버 href.
 */
private fun resolveEpubCoverDecision(zip: FileSystem, packageData: PackageData): EpubCoverDecision {
    val coverHref = packageData.coverHref
    val coverBytes = coverHref?.let { zip.readBytesOrNull(it.toPath()) }
    val firstLinearItem = packageData.spineItems.firstOrNull { it.linear }
    val spineOrder0Skipped = if (coverHref != null && firstLinearItem != null) {
        val itemPath = firstLinearItem.path
        val xhtml = zip.readUtf8OrNull(itemPath.toPath())
        xhtml != null && isPureCoverXhtml(
            parseXhtmlContent(
                xhtml = xhtml,
                baseOffset = 0L,
                resolveImageHref = { source -> resolveContainerHref(itemPath, source) },
                css = linkedCss(xhtml, itemPath, zip, mutableMapOf()),
            ),
            coverHref,
        )
    } else {
        false
    }
    return EpubCoverDecision(
        coverHref = coverHref,
        coverBytes = coverBytes,
        hasCoverSection = coverHref != null && coverBytes != null,
        spineOrder0Skipped = spineOrder0Skipped,
    )
}

/**
 * [EpubDocumentParser.parseWithCover]가 책 자체의 커버 그림에 부여하는 합성 섹션 — 커버가
 * 있다면([EpubCoverDecision.hasCoverSection]) 점진적이든 아니든 책이 파싱하는 맨 처음
 * 대상이므로 항상 섹션 0이다.
 *
 * @param coverDecision 책에 대해 확정된 커버 상태; [resolveEpubCoverDecision] 참고.
 * @param documentTitle 합성 섹션에 붙일 제목.
 * @return 한 글자짜리 커버 섹션과 그 단일 [ReaderBlockKind.COVER_IMAGE] 블록, 또는 [coverDecision]에
 *   만들어낼 커버 섹션이 없으면 null.
 */
internal fun buildEpubCoverSection(coverDecision: EpubCoverDecision, documentTitle: String): EpubParsedSection? {
    val coverHref = coverDecision.coverHref?.takeIf { coverDecision.hasCoverSection } ?: return null
    val range = TextRange(0L, 1L)
    return EpubParsedSection(
        section = ReaderSection(index = 0, text = " ", range = range, title = documentTitle),
        blocks = listOf(ReaderBlock(kind = ReaderBlockKind.COVER_IMAGE, range = range, imageHref = coverHref, label = documentTitle)),
    )
}

/**
 * [EpubImportContainer.linearSpineItems]의 스파인 항목 [spinePosition]을, 같은 위치에서
 * [EpubDocumentParser.parseWithCover] 자체의 루프가 하는 것과 정확히 똑같이 파싱해, 텍스트를
 * [baseOffset]에 배치하고 [sectionIndex]로 번호를 매긴다. 그 루프 자체가 완전히 건너뛸 수 있는
 * 유일한 경우 — [spinePosition] 0이 [EpubImportContainer.coverDecision] 자체의 커버 그림 외에
 * 아무것도 아닌 경우 — 이거나, xhtml을 전혀 읽을 수 없는 스파인 항목이면 null을 반환한다.
 *
 * @param container 열린 임포트 컨테이너; [openEpubImportContainer] 참고.
 * @param spinePosition 파싱할 항목의, [EpubImportContainer.linearSpineItems] 내 인덱스.
 * @param sectionIndex 결과에 기록할 섹션 인덱스.
 * @param baseOffset 이 섹션의 텍스트가 시작해야 할 절대 오프셋.
 * @return 파싱된 섹션과 그 블록들, 또는 위에서 설명한 건너뛰기 경우엔 null.
 */
internal fun parseEpubSpineItem(
    container: EpubImportContainer,
    spinePosition: Int,
    sectionIndex: Int,
    baseOffset: Long,
): EpubParsedSection? {
    val spineItem = container.linearSpineItems.getOrNull(spinePosition) ?: return null
    val xhtml = container.zip.readUtf8OrNull(spineItem.path.toPath()) ?: return null
    val content = parseXhtmlContent(
        xhtml = xhtml,
        baseOffset = baseOffset,
        resolveImageHref = { source -> resolveContainerHref(spineItem.path, source) },
        css = linkedCss(xhtml, spineItem.path, container.zip, container.linkedCssCache),
    )
    val coverHref = container.coverDecision.coverHref
    if (spinePosition == 0 && coverHref != null && isPureCoverXhtml(content, coverHref)) return null
    val sectionTitle = content.headingTitle
        ?: firstHeadingTitle(content, baseOffset)
        ?: spineItem.item.title
        ?: spineItem.item.id
    val range = TextRange(baseOffset, baseOffset + content.text.length)
    return EpubParsedSection(
        section = ReaderSection(index = sectionIndex, text = content.text, range = range, title = sectionTitle),
        blocks = content.blocks,
    )
}

/**
 * 모든 섹션이 파악되면 [container]의 내비게이션을 해석한다 — 매 배치가 끝날 때마다가 아니라,
 * 점진적 임포트의 마지막 배치가 스파인을 다 소진했을 때 단 한 번만 호출된다.
 * [sectionPathByIndex]는 스파인 위치의 순수 함수이며(DocumentRepositoryImpl.buildSectionPathByIndex
 * 참고), 배치가 도중에 기억해야 할 무언가가 아니다. 섹션 내부의 프래그먼트를 대상으로 하는 nav
 * 항목은 그 프래그먼트의 정확한 오프셋이 아니라 해당 섹션 자체의 시작으로 해석된다 —
 * [resolveNavigation]이 위치를 찾을 수 없는 프래그먼트에 이미 제공하는 것과 같은 우아한
 * 폴백이다.
 *
 * @param container 내비게이션 문서/NCX를 읽어올 열린 임포트 컨테이너.
 * @param sectionPathByIndex 지금까지 만들어진 모든 섹션. 인덱스를 키로, 컨테이너 경로에 매핑됨.
 * @param coverSectionIndex 합성 커버 섹션의 인덱스, 또는 책에 그것이 없으면 null.
 * @param firstReadableContentSectionIndex 텍스트가 비어 있지 않은 첫 번째 섹션의 인덱스, 또는
 *   그런 게 없으면 null — [resolveNavigation]이 사용하는 것과 같은 방식으로, 커버를 대상으로
 *   하는 nav 항목이 대신 리다이렉트되어야 할 곳으로 쓰인다.
 * @return 해석된 [ReaderNavigation].
 */
internal fun resolveEpubNavigationAtCompletion(
    container: EpubImportContainer,
    sectionPathByIndex: Map<Int, String>,
    coverSectionIndex: Int?,
    firstReadableContentSectionIndex: Int?,
): ReaderNavigation {
    val packageData = container.packageData
    val parsedNavigation = packageData.navigationItemPath?.let { navPath ->
        container.zip.readUtf8OrNull(navPath.toPath())?.let(::parseEpubNavDocument)
    } ?: packageData.ncxPath?.let { ncxPath ->
        container.zip.readUtf8OrNull(ncxPath.toPath())?.let(::parseNcxDocument)
    } ?: ParsedNavigation()
    return resolveNavigation(
        navigation = parsedNavigation,
        sectionPathByIndex = sectionPathByIndex,
        sectionStartOffsets = emptyMap(),
        sectionAnchorOffsets = emptyMap(),
        coverSpineIndex = coverSectionIndex,
        firstReadableContentSectionIndex = firstReadableContentSectionIndex,
        navigationBasePath = packageData.navigationItemPath ?: packageData.ncxPath ?: container.opfPath.toString(),
    )
}

/**
 * OPF 매니페스트의 `<item>` 하나 — id, 대상, 제목, 미디어 타입, 그리고 그것이 지닌 속성들
 * (`cover-image` 같은). private이 아니라 internal인 이유: [EpubImportContainer]가 이것들을
 * `DocumentRepositoryImpl`의 점진적 임포트에 노출하는데, 그쪽은 스파인 목록과 매니페스트
 * 항목을 한꺼번에가 아니라 배치 단위로 필요로 한다.
 */
internal data class ManifestItem(
    /** 매니페스트 항목 자체의 `id` 속성. 스파인 `itemref`의 `idref`가 참조한다. */
    val id: String,
    /**
     * 항목의 `href`. OPF 자체의 위치에 상대적이며, [resolveContainerHref]로 컨테이너 경로로
     * 해석된다.
     */
    val href: String,
    /** 항목의 `title` 속성, 있다면. */
    val title: String?,
    /** 항목의 `media-type` 속성, 있다면. */
    val mediaType: String?,
    /**
     * 항목의 `properties` 속성(`cover-image`나 `nav` 같은 공백 구분 토큰들), 있다면.
     */
    val properties: String?,
)

/** 스파인 `<itemref>` 하나. 그것이 참조하는 매니페스트 항목과 그 항목의 컨테이너 경로로 해석된다. */
internal data class SpineItem(
    /** 이 스파인 항목이 참조하는 매니페스트 항목. */
    val item: ManifestItem,
    /** 컨테이너 내 경로로 해석된 [ManifestItem.href]. */
    val path: String,
    /**
     * `<itemref>`가 `linear="no"`를 선언하면 false — 이 항목이 정상적인 읽기 순서에 속하지
     * 않는다는 책 자체의 지시이며, 절대 섹션이 되지 않는다.
     */
    val linear: Boolean,
)

/** 매니페스트 항목과 아직 매칭되지 않은 스파인 `<itemref>` 하나. */
private data class SpineItemRef(
    /** [ManifestItem.id]와 매칭되는 `idref` 속성. */
    val idref: String,
    /** `linear="no"`일 때 false; [SpineItem.linear] 참고. */
    val linear: Boolean,
)

/** 단발성이든 점진적이든 EPUB 파싱을 주도할 준비가 된, OPF 전체의 파싱된 내용. */
internal data class PackageData(
    /** 책의 `dc:title`, 또는 OPF가 아무것도 선언하지 않았으면 null. */
    val documentTitle: String?,
    /** 스파인 순서대로, 매니페스트 항목과 컨테이너 경로로 해석된 모든 스파인 `<itemref>`. */
    val spineItems: List<SpineItem>,
    /**
     * EPUB 3 내비게이션 문서(`properties="nav"`인 매니페스트 항목)의 컨테이너 경로, 또는 책에
     * 그것이 없으면 null.
     */
    val navigationItemPath: String?,
    /** EPUB 2 NCX 문서의 컨테이너 경로, 또는 책에 그것이 없으면 null. */
    val ncxPath: String?,
    /**
     * 커버 이미지의 컨테이너 경로, 또는 OPF가 래스터 커버를 선언하지 않았으면 null;
     * [findEpubCoverHref] 참고.
     */
    val coverHref: String?,
)

/** 내비게이션 항목의 `href`. 컨테이너 경로와 (있다면) 프래그먼트로 분리된 것. */
private data class ResolvedReference(
    /** 컨테이너 상대 경로. 어떤 `#fragment`든 제거된 상태. */
    val path: String,
    /** href에 프래그먼트가 있었다면 그 `#fragment` 부분. */
    val fragment: String?,
)

/** [path]의 전체 내용을 UTF-8로 읽는다. 존재하지 않거나 읽을 수 없으면 null. */
private fun FileSystem.readUtf8OrNull(path: Path): String? =
    runCatching {
        val source = source(path).buffer()
        try {
            source.readUtf8()
        } finally {
            source.close()
        }
    }.getOrNull()

/**
 * [chapterPath]가 링크하는 모든 스타일시트의 캐스케이드. `<link>` 순서대로 처리해 동점일 때는
 * 더 나중 시트가 이긴다 — 이 책들이 그림 너비 외의 CSS 대부분을 보관하는 곳이 바로 여기다.
 * 연결된 스타일시트의 집합(그것들의 href를 이어붙인 것)으로 캐시되는데, 책은 보통 수백 개
 * 챕터에 걸쳐 같은 몇 안 되는 시트를 재사용하기 때문이다.
 *
 * @param xhtml `<link rel="stylesheet">` 태그를 찾기 위해 스캔되는, 챕터의 원본 마크업.
 * @param chapterPath 각 연결된 시트의 `href`를 해석하는 데 쓰이는, 챕터 자체의 컨테이너 경로.
 * @param zip 연결된 스타일시트를 읽어올 아카이브.
 * @param cache 스타일시트 집합에서 파싱된 [EpubCss]로의 캐시. 호출자가 챕터 전반에 걸쳐
 *   공유하여, 반복되는 시트 집합이 한 번만 파싱되게 한다.
 * @return 챕터가 어떤 스타일시트도 링크하지 않으면 [EpubCss.Empty], 아니면 병합된 캐스케이드.
 */
private fun linkedCss(
    xhtml: String,
    chapterPath: String,
    zip: FileSystem,
    cache: MutableMap<String, EpubCss>,
): EpubCss {
    val hrefs = linkedStyleSheetHrefs(xhtml, chapterPath)
    if (hrefs.isEmpty()) return EpubCss.Empty
    val key = hrefs.joinToString("|")
    cache[key]?.let { return it }
    val parsed = EpubCss.parseSources(
        hrefs.mapNotNull { href ->
            zip.readUtf8OrNull(href.toPath())?.let { css -> CssStyleSheetSource(path = href, css = css) }
        },
    )
    cache[key] = parsed
    return parsed
}

/**
 * [xhtml]이 `<link rel="stylesheet">`로 링크하는 모든 스타일시트의 컨테이너 경로. 문서 순서대로.
 *
 * @param xhtml 챕터의 원본 마크업.
 * @param chapterPath 각 `href`를 해석하는 데 쓰이는, 챕터 자체의 컨테이너 경로.
 */
private fun linkedStyleSheetHrefs(xhtml: String, chapterPath: String): List<String> =
    StyleSheetLinkRegex.findAll(xhtml)
        .map { match -> parseAttributes(match.value) }
        .filter { attributes -> attributes["rel"]?.contains("stylesheet", ignoreCase = true) == true }
        .mapNotNull { attributes -> attributes["href"]?.let { resolveContainerHref(chapterPath, it) } }
        .toList()

/**
 * 모든 이미지 블록에 그림 자신의 바이트에서 읽은 크기를 채워 넣는다: 종횡비, 그리고 마크업이나
 * 스타일시트 어느 쪽도 크기를 선언하지 않았을 때 그것을 결정하는 고유 너비. [blocks]를 제자리에서
 * 변경한다.
 *
 * private이 아니라 internal인 이유: `DocumentRepositoryImpl.importNextSections`가 점진적
 * 임포트 도중 새로 파싱된 블록 배치마다 이것을 한 번씩 호출한다 — 단발성 파싱이 책 전체에
 * 대해 한 번 실행하는 것과 같은 크기 계산 과정이다.
 *
 * @param blocks 제자리에서 패치할 블록들; [ReaderBlockKind.IMAGE]와 [ReaderBlockKind.COVER_IMAGE]
 *   블록만 다룬다.
 * @param zip 커버가 아닌 각 이미지의 헤더를 읽어올 아카이브.
 * @param coverHref 책의 커버 경로. 이미 디코딩된 [coverBytes]를 두 번째로 읽는 대신 재사용할 수
 *   있게 해준다; 책에 커버가 없으면 null.
 * @param coverBytes 커버의 이미 디코딩된 바이트; 커버가 없거나 그 바이트를 구할 수 없었으면 null.
 */
internal fun fillIntrinsicImageSizes(
    blocks: MutableList<ReaderBlock>,
    zip: FileSystem,
    coverHref: String?,
    coverBytes: ByteArray?,
) {
    val hrefs = blocks.asSequence()
        .filter { it.kind == ReaderBlockKind.IMAGE || it.kind == ReaderBlockKind.COVER_IMAGE }
        .mapNotNull { it.imageHref }
        .toSet()
    if (hrefs.isEmpty()) return

    val sizes = mutableMapOf<String, Pair<Int, Int>>()
    if (coverHref != null && coverHref in hrefs && coverBytes != null) {
        sniffImageDimensions(coverBytes)?.let { sizes[coverHref] = it }
    }
    (hrefs - sizes.keys).forEach { href ->
        val header = zip.readHeaderBytesOrNull(href.toPath(), ImageHeaderSniffBytes) ?: return@forEach
        sniffImageDimensions(header)?.let { sizes[href] = it }
    }
    if (sizes.isEmpty()) return

    for (index in blocks.indices) {
        val block = blocks[index]
        val (width, height) = block.imageHref?.let(sizes::get) ?: continue
        blocks[index] = block.copy(
            imageAspectRatio = block.imageAspectRatio ?: (width.toFloat() / height),
            imageNaturalWidthPx = block.imageNaturalWidthPx ?: width,
        )
    }
}

/**
 * [path]의 시작부터 [maxBytes]까지 읽는다 — 훨씬 더 클 수도 있는 파일 전체를 읽지 않고 헤더에서
 * 이미지 크기를 알아내는 데 딱 필요한 만큼만.
 *
 * 한 번의 `read()` 호출이 남은 바이트가 더 있어도 요청량을 다 채운다는 보장은 없으므로, 이는
 * [readBytesOrNull]이 전체 파일에 대해 하는 것과 같은 방식으로 한도(또는 EOF)까지 반복한다.
 *
 * @receiver [path]를 읽어올 아카이브.
 * @param path 읽을 엔트리.
 * @param maxBytes 읽을 바이트 수의 상한.
 * @return 엔트리 시작부터 최대 [maxBytes]바이트, 또는 열 수 없거나 읽는 도중 예외가 나면 null.
 */
private fun FileSystem.readHeaderBytesOrNull(path: Path, maxBytes: Int): ByteArray? {
    val source = runCatching { source(path).buffer() }.getOrNull() ?: return null
    return try {
        val buffer = Buffer()
        while (buffer.size < maxBytes) {
            if (source.read(buffer, maxBytes - buffer.size) == -1L) break
        }
        buffer.readByteArray()
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    }
}

/**
 * [path]의 전체 내용을 읽되 [MAX_EPUB_IMAGE_BYTES]로 상한을 둬서, 손상됐거나 예상외로 거대한
 * 엔트리 하나가 커버나 임베드 이미지가 필요로 해야 할 메모리를 초과해 날려버리지 못하게 한다.
 *
 * @receiver [path]를 읽어올 아카이브.
 * @param path 읽을 엔트리.
 * @return 엔트리의 바이트, 또는 열 수 없었거나, 읽는 도중 예외가 났거나, [MAX_EPUB_IMAGE_BYTES]를
 *   초과했으면 null.
 */
private fun FileSystem.readBytesOrNull(path: Path): ByteArray? {
    val source = runCatching { source(path).buffer() }.getOrNull() ?: return null
    return try {
        val buffer = Buffer()
        var totalBytes = 0L
        while (true) {
            val read = source.read(buffer, 8_192)
            if (read == -1L) break
            totalBytes += read
            if (totalBytes > MAX_EPUB_IMAGE_BYTES) return null
        }
        buffer.readByteArray()
    } catch (_: Throwable) {
        null
    } finally {
        source.close()
    }
}

/**
 * `META-INF/container.xml`의 `<rootfile full-path="...">`에서 얻는 OPF의 경로, 또는 그것이
 * 없거나 형식이 잘못됐으면 null.
 */
private fun findRootFilePath(containerXml: String): Path? =
    Regex("""full-path\s*=\s*["']([^"']+)["']""")
        .find(containerXml)
        ?.groupValues
        ?.get(1)
        ?.toPath()

/**
 * [opf]를 [PackageData]로 파싱한다: 제목, (매니페스트 항목과 컨테이너 경로로 해석된) 스파인,
 * 내비게이션 문서/NCX 경로, 커버 href.
 *
 * @param opf OPF 패키지 문서의 원본 XML.
 * @param opfPath OPF 자체의 컨테이너 경로. 참조되는 모든 항목의 `href`를 OPF 자신이 위치한
 *   곳을 기준으로 해석하는 데 쓰인다.
 */
private fun parsePackageData(opf: String, opfPath: Path): PackageData {
    val manifest = parseManifest(opf)
    val spineRefs = parseSpine(opf)
    val spineItems = spineRefs.mapNotNull { ref ->
        val item = manifest[ref.idref]?.takeIf { it.isChapterItem() } ?: return@mapNotNull null
        val path = resolveContainerHref(opfPath.toString(), item.href) ?: return@mapNotNull null
        SpineItem(item = item, path = path, linear = ref.linear)
    }
    val spineTocId = Regex("""(?is)<spine\b[^>]*toc\s*=\s*["']([^"']+)["']""")
        .find(opf)
        ?.groupValues
        ?.get(1)
    val navPath = manifest.values
        .firstOrNull { navTypeTokens(it.properties).contains("nav") }
        ?.let { resolveContainerHref(opfPath.toString(), it.href) }
    val ncxPath = spineTocId?.let { manifest[it] }
        ?.takeIf { it.mediaType.equals(NcxMediaType, ignoreCase = true) }
        ?.let { resolveContainerHref(opfPath.toString(), it.href) }
        ?: manifest.values.firstOrNull { it.mediaType.equals(NcxMediaType, ignoreCase = true) }
            ?.let { resolveContainerHref(opfPath.toString(), it.href) }
    return PackageData(
        documentTitle = parseDcTitle(opf),
        spineItems = spineItems,
        navigationItemPath = navPath,
        ncxPath = ncxPath,
        coverHref = findEpubCoverItem(opf, manifest, opfPath),
    )
}

/**
 * OPF의 `<dc:title>`. 내부 마크업이 제거되고 엔티티가 디코딩된 상태이며, 없거나 비어 있으면
 * null.
 */
private fun parseDcTitle(opf: String): String? =
    Regex("""(?is)<dc:title\b[^>]*>(.*?)</dc:title>""")
        .find(opf)
        ?.groupValues
        ?.get(1)
        ?.let(::stripMarkup)
        ?.takeIf(String::isNotBlank)

/**
 * OPF의 `<manifest>`에 있는 모든 `<item>`. `id`를 키로 함; `id`나 `href`가 없는 항목은
 * 버려진다.
 */
private fun parseManifest(opf: String): Map<String, ManifestItem> =
    Regex("""(?is)<item\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            val id = attrs["id"] ?: return@mapNotNull null
            val href = attrs["href"] ?: return@mapNotNull null
            id to ManifestItem(
                id = id,
                href = decodeXmlEntities(href),
                title = attrs["title"]?.let(::decodeXmlEntities),
                mediaType = attrs["media-type"],
                properties = attrs["properties"],
            )
        }
        .toMap()

/** OPF의 `<spine>`에 있는 모든 `<itemref>`. 문서 순서대로; `idref`가 없는 항목은 버려진다. */
private fun parseSpine(opf: String): List<SpineItemRef> =
    Regex("""(?is)<itemref\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            val idref = attrs["idref"] ?: return@mapNotNull null
            SpineItemRef(idref = idref, linear = !attrs["linear"].equals("no", ignoreCase = true))
        }
        .toList()

/**
 * 책의 커버 이미지 경로. 실제 EPUB들이 실제로 커버를 선언하는 순서대로 시도한다; 정확한 폴백
 * 체인은 [findEpubCoverItem] 참고.
 *
 * 매니페스트를 직접 파싱하므로, OPF 텍스트만 손에 쥔 호출자를 위한 자기완결적 진입점으로
 * 남는다 — 스파인을 위해 이미 매니페스트를 파싱해 둔 [parsePackageData]는 두 번째로 파싱하는
 * 것을 피하기 위해 대신 매니페스트를 받는 [findEpubCoverItem] 오버로드를 호출한다.
 *
 * @param opf OPF 패키지 문서의 원본 XML.
 * @param opfPath non-null이면 찾은 항목의 `href`를 컨테이너 경로로 해석한다; null이면 항목의
 *   원본 `href`가 해석되지 않은 채로 반환된다.
 * @return 커버의 경로, 또는 OPF가 래스터 커버 이미지를 전혀 선언하지 않았으면 null.
 */
internal fun findEpubCoverHref(opf: String, opfPath: Path? = null): String? =
    findEpubCoverItem(opf, parseManifest(opf), opfPath)

/**
 * 책의 커버인 매니페스트 항목을 찾는다. 실제 EPUB들이 실제로 커버를 선언하는 방식을 명시적인
 * 것부터 그렇지 않은 순서로 시도한다: EPUB 3의 `properties="cover-image"` 항목; 실패하면 EPUB
 * 2의 매니페스트 id를 가리키는 `<meta name="cover" content="...">` 포인터; 그마저 실패하면
 * 자신의 id나 href가 커버임을 암시하는 아무 래스터 이미지 항목이나. 모든 후보는 래스터
 * 이미지([ManifestItem.isRasterImage])여야도 한다 — 이 리더는 커버를 래스터 바이트로
 * 디코딩하므로 SVG 커버는 여기서 해석되지 않는다.
 *
 * [opf]의 `<item>`들을 다시 파싱하는 대신 이미 파싱된 [manifest]를 받는다. 그래서 매니페스트를
 * 이미 가진(스파인 빌드에 필요해서) 호출자는 매니페스트 스캔 비용을 한 번만 치른다; [opf]는
 * 여전히, 매니페스트 바깥에 있는 EPUB 2 `<meta name="cover">` 포인터를 위해 필요하다.
 *
 * @param opf OPF 패키지 문서의 원본 XML. EPUB 2 커버 `<meta>` 포인터를 위해서만 읽힌다.
 * @param manifest 호출자가 이미 파싱해 둔, id를 키로 하는 OPF의 매니페스트.
 * @param opfPath non-null이면 찾은 항목의 `href`를 컨테이너 경로로 해석한다; null이면 항목의
 *   원본 `href`가 해석되지 않은 채로 반환된다.
 * @return 커버 항목의 경로, 또는 세 가지 방법 중 어느 것도 래스터 커버를 찾지 못하면 null.
 */
private fun findEpubCoverItem(opf: String, manifest: Map<String, ManifestItem>, opfPath: Path? = null): String? {
    val raw = manifest.values.firstOrNull { it.isCoverImageProperty() && it.isRasterImage() }
        ?: findCoverMetaId(opf)?.let { manifest[it] }?.takeIf { it.isRasterImage() }
        ?: manifest.values.firstOrNull { it.isRasterImage() && it.hasCoverHint() }
        ?: return null
    return opfPath?.let { resolveContainerHref(it.toString(), raw.href) } ?: raw.href
}

/**
 * EPUB 2의 `<meta name="cover" content="...">`가 가리키는 매니페스트 id, 또는 OPF에 그런 태그가
 * 없으면 null.
 */
private fun findCoverMetaId(opf: String): String? =
    Regex("""(?is)<meta\b[^>]*>""")
        .findAll(opf)
        .mapNotNull { match ->
            val attrs = parseAttributes(match.value)
            attrs["content"]?.takeIf { attrs["name"]?.equals("cover", ignoreCase = true) == true }
        }
        .firstOrNull()

/**
 * 이 항목의 미디어 타입이 SVG가 아닌 `image/` 타입인지 여부 — 이 리더가 커버로 디코딩할 수 있는
 * 래스터 형식들.
 */
private fun ManifestItem.isRasterImage(): Boolean {
    val mediaType = mediaType ?: return false
    return mediaType.startsWith("image/", ignoreCase = true) && !mediaType.contains("svg", ignoreCase = true)
}

/**
 * 이 매니페스트 항목이 읽을 수 있는 챕터 콘텐츠인지 여부: XHTML 미디어 타입이거나, 미디어
 * 타입 자체가 그것을 말해주지 않을 때는 `.html`/`.xhtml` href.
 */
private fun ManifestItem.isChapterItem(): Boolean {
    val mediaType = mediaType.orEmpty()
    return mediaType.contains("xhtml", ignoreCase = true) ||
        href.endsWith(".html", ignoreCase = true) ||
        href.endsWith(".xhtml", ignoreCase = true)
}

/**
 * 이 항목 자체의 id나 href가 `"cover"`를 포함하는지 여부 — [findEpubCoverItem]의 세 폴백 중
 * 가장 약한 것.
 */
private fun ManifestItem.hasCoverHint(): Boolean =
    id.contains("cover", ignoreCase = true) || href.contains("cover", ignoreCase = true)

/** 이 항목이 EPUB 3의 `properties="cover-image"` 토큰을 선언하는지 여부. */
private fun ManifestItem.isCoverImageProperty(): Boolean = navTypeTokens(properties).contains("cover-image")

/**
 * 태그의 `name="value"`/`name='value'` 속성 쌍들을 소문자 키의 맵으로 파싱한다. OPF/NCX/
 * 내비게이션-문서 마크업용 — 이 문서들은 블록/인라인 해석이 필요 없으므로, [EpubXhtmlParser]
 * 자체의 태그 파싱과는 별개의, 더 단순한 처리 과정이다.
 *
 * @param tag 한 태그의 원본 텍스트, 예를 들어 호출자의 정규식이 매칭한 `<item id="..." href="..."/>`.
 */
internal fun parseAttributes(tag: String): Map<String, String> =
    Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        .findAll(tag)
        .associate { match ->
            match.groupValues[1].lowercase() to (match.groupValues[2].ifEmpty { match.groupValues[3] })
        }

/**
 * 마크업에서 곧바로 가져온 원본 `src`/`href` 값인 [source]를, 컨테이너 내 [chapterPath] 자체의
 * 위치를 기준으로 해석해, 여기 있는 다른 모든 함수가 ZIP에 대해 바로 조회할 수 있는 경로로
 * 만든다.
 *
 * @param chapterPath [source]가 작성된 문서의 컨테이너 경로; null이면 대신 컨테이너 루트를
 *   기준으로 해석한다.
 * @param source 아직 퍼센트 인코딩과 엔티티 인코딩이 되어 있고, `#fragment`를 갖고 있을 수도
 *   있는 원본 참조.
 * @return 해석된 컨테이너 경로(프래그먼트는 제거되고 `..`/`.` 세그먼트는 정규화됨), 또는
 *   [source]가 비어 있거나, `data:` URI이거나, 이 리더가 가져올 수 없는 원격 `http(s)://`
 *   URL이면 null.
 */
internal fun resolveContainerHref(chapterPath: String?, source: String): String? {
    val decodedSource = decodePercentEncoding(decodeXmlEntities(source.trim()))
    if (decodedSource.isEmpty() || decodedSource.startsWith("data:")) return null
    if (decodedSource.startsWith("http://") || decodedSource.startsWith("https://")) return null
    val cleaned = decodedSource.substringBefore('#')
    if (cleaned.isEmpty()) return null
    if (cleaned.startsWith("/")) return cleaned.removePrefix("/")
    val parent = chapterPath?.toPath()?.parent ?: return cleaned
    return parent.resolve(cleaned).normalized().toString().removePrefix("/")
}

/**
 * [resolveContainerHref]와 비슷하지만, 내비게이션 항목의 `href`용이다: `#fragment`를 버리는
 * 대신 보존하며, 경로가 전혀 없는 단독 `#fragment`는 [basePath] 자체를 가리키는 것으로 취급한다.
 *
 * @param basePath [source]가 작성된 내비게이션 문서의 컨테이너 경로.
 * @param source 아직 퍼센트 인코딩과 엔티티 인코딩이 되어 있는 원본 `href`.
 * @return 해석된 경로와 선택적 프래그먼트, 또는 [source]가 디코딩해도 쓸 수 있는 게 없으면 null.
 */
private fun resolveContainerReference(basePath: String?, source: String): ResolvedReference? {
    val decodedSource = decodePercentEncoding(decodeXmlEntities(source.trim()))
    if (decodedSource.isEmpty()) return null
    val fragment = decodedSource.substringAfter('#', missingDelimiterValue = "").takeIf(String::isNotEmpty)
    val pathSource = decodedSource.substringBefore('#')
    val resolvedPath = when {
        pathSource.isEmpty() -> basePath?.removePrefix("/")
        else -> resolveContainerHref(basePath, decodedSource)
    } ?: return null
    return ResolvedReference(path = resolvedPath, fragment = fragment)
}

/**
 * [value] 안의 `%XX` 퍼센트 인코딩을 바이트 단위로 디코딩한 뒤, 그 결과를 다시 UTF-8로
 * 디코딩한다 — 마크업의 원본 `href`는 이런 식으로 한 번에 한 바이트씩 인코딩된 멀티바이트
 * UTF-8 문자를 정당하게 담을 수 있다.
 *
 * @param value 원본, 아마도 퍼센트 인코딩된 참조.
 * @return 형식이 올바른 모든 `%XX` 삼중항이 디코딩된 [value]; 형식이 잘못된 것(잘못된
 *   16진수, 또는 문자열 끝에 너무 가까운 경우)은 리터럴 텍스트로 남는다.
 */
private fun decodePercentEncoding(value: String): String {
    if ('%' !in value) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 < value.length) {
            val code = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (code != null) {
                bytes += code.toByte()
                index += 3
                continue
            }
        }
        char.toString().encodeToByteArray().forEach { bytes += it }
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

/**
 * 파싱된 챕터가 책 자체의 커버 그림이 반복된 것에 지나지 않는지 여부 — 합성 커버 섹션이 이미
 * 그것을 보여주기 때문에 [EpubDocumentParser.parseWithCover]가 챕터로서 건너뛰는 경우.
 *
 * 그림 하나는 평탄화된 텍스트에서 [com.tedd.teddreader.core.common.model.ReaderObjectReplacementChar]
 * 플레이스홀더 문자 하나를 차지하므로, "읽을 수 있는 텍스트 없음"은 그저 비어 있음이 아니라 그
 * 플레이스홀더들 자체를 넘어선 텍스트가 없음을 뜻해야 한다 — 이것이 바로 [isBlankIgnoringObjects]가
 * 확인하는 것이다.
 *
 * @param content 챕터의 이미 파싱된 콘텐츠.
 * @param coverHref 챕터 내 모든 이미지가, 페이지에 그저 혼자 있을 뿐인 다른 이미지가 아니라
 *   바로 그 그림임을 확인하기 위한, 책의 커버 이미지 경로.
 */
private fun isPureCoverXhtml(
    content: XhtmlContent,
    coverHref: String,
): Boolean {
    if (!content.text.isBlankIgnoringObjects()) return false
    val imageBlocks = content.blocks.filter { it.kind == ReaderBlockKind.IMAGE }
    return imageBlocks.isNotEmpty() && content.blocks.all { it.kind == ReaderBlockKind.IMAGE } && imageBlocks.all { it.imageHref == coverHref }
}

/**
 * 챕터의 첫 헤딩 블록 자체의 텍스트. 트리밍됨 — [XhtmlContent.headingTitle](이미지 전용 헤딩의
 * `title` 속성) 다음의 차선책 섹션 제목.
 *
 * @param content 챕터의 이미 파싱된 콘텐츠.
 * @param baseOffset [content]가 파싱될 때 쓰인 것과 같은 기준 오프셋. 그 블록들의 절대 범위를
 *   [content] 자체 텍스트 내 인덱스로 되돌리는 데 필요하다.
 * @return 첫 헤딩의 트리밍된 텍스트, 또는 챕터에 헤딩 블록이 없거나 그 헤딩의 텍스트가 비어
 *   있으면 null.
 */
private fun firstHeadingTitle(
    content: XhtmlContent,
    baseOffset: Long,
): String? =
    content.blocks.firstOrNull { it.kind == ReaderBlockKind.HEADING }?.let { block ->
        val start = (block.range.start - baseOffset).toInt().coerceAtLeast(0)
        val end = (block.range.end - baseOffset).toInt().coerceAtMost(content.text.length)
        if (end <= start) null else content.text.substring(start, end).trim().takeIf(String::isNotBlank)
    }

/**
 * [navigation]의 원본 항목들(EPUB 3 nav 문서 또는 EPUB 2 NCX에서 온)을, 리더가 점프할 수 있는
 * 절대 섹션과 오프셋으로 주소가 매겨진 [ReaderNavigationItem]들로 바꾼다.
 *
 * 항목의 `href`는 먼저 [sectionPathByIndex]와 정확히 매칭을 시도한 다음, 접미사로 매칭을
 * 시도한다(내비게이션 문서가 대상보다 한 디렉터리 위에 있으면 보통 이 파서가 해석한 것보다 더
 * 짧은 상대 경로로 그것들을 링크한다) — 둘 다 매칭되지 않는 항목은 버려진다. 점프할 곳이
 * 없기 때문이다. 매칭된 섹션이 합성 커버 섹션([coverSpineIndex])인데 그 항목 자체의 제목이
 * 인식 가능한 커버 라벨이 아니면([String.isVisibleCoverLabel] 참고), 점프는 대신
 * [firstReadableContentSectionIndex]로 리다이렉트된다: 이는 목차에 실제 제목으로 진짜 첫
 * 챕터가 올라가 있지만, 그 챕터 파일이 우연히 합성 섹션이 이미 보여주는 것과 같은 커버 전용
 * XHTML인 책을 다룬다 — 이 경우 리더는 커버로 돌려보내지는 게 아니라 거의 확실히 첫 실제
 * 콘텐츠를 의도한 것이다. 항목의 프래그먼트는, 기록되어 있다면 해당 섹션 내 그 프래그먼트
 * 자체의 앵커 오프셋으로 해석되고, 그렇지 않으면 전체 항목을 실패시키는 대신 섹션 자체의
 * 시작(오프셋 0)으로 폴백한다.
 *
 * @param navigation 아직 어떤 섹션으로도 주소가 매겨지지 않은, 원본으로 파싱된 내비게이션
 *   (nav 문서 또는 NCX).
 * @param sectionPathByIndex 모든 섹션. 인덱스를 키로, 자신의 컨테이너 경로에 매핑됨.
 * @param sectionStartOffsets 각 섹션 자체의 절대 시작 오프셋. 인덱스를 키로 함 — 앵커의 절대
 *   오프셋을 그 섹션에 상대적인 값으로 되돌리는 데 쓰인다.
 * @param sectionAnchorOffsets 각 섹션 자체의 `id`/`name`/`xml:id` 앵커와 그 절대 오프셋. 섹션
 *   인덱스를 키로 함.
 * @param coverSpineIndex 합성 커버 섹션의 인덱스, 또는 책에 그것이 없으면 null.
 * @param firstReadableContentSectionIndex 텍스트가 비어 있지 않은 첫 번째 섹션의 인덱스. 위에서
 *   설명한 리다이렉트 대상으로 쓰인다; null이면 커버가 아닌 첫 섹션으로, 그것도 없으면 0으로
 *   폴백한다.
 * @param navigationBasePath 내비게이션 문서 자체가 위치한 컨테이너 경로. 각 항목의 상대
 *   `href`를 해석하는 데 쓰인다.
 * @return 해석된 [ReaderNavigation]. [navigation] 자체의 헤딩 라벨은 그대로 유지한다.
 */
private fun resolveNavigation(
    navigation: ParsedNavigation,
    sectionPathByIndex: Map<Int, String>,
    sectionStartOffsets: Map<Int, Long>,
    sectionAnchorOffsets: Map<Int, Map<String, Long>>,
    coverSpineIndex: Int?,
    firstReadableContentSectionIndex: Int?,
    navigationBasePath: String,
): ReaderNavigation {
    val sectionByPath = sectionPathByIndex.entries.associateBy({ it.value }, { it.key })
    val firstContentSection = firstReadableContentSectionIndex
        ?: sectionPathByIndex.keys.firstOrNull { it != coverSpineIndex }
        ?: 0
    val items = navigation.entries.mapNotNull { entry ->
        val resolved = resolveContainerReference(navigationBasePath, entry.href) ?: return@mapNotNull null
        val matchingSection = sectionByPath[resolved.path]
            ?: sectionByPath.entries.firstOrNull { (path, _) -> path.endsWith(resolved.path) }?.value
            ?: return@mapNotNull null
        val spineIndex = if (coverSpineIndex != null && matchingSection == coverSpineIndex && !entry.title.isVisibleCoverLabel()) {
            firstContentSection
        } else {
            matchingSection
        }
        val offset = resolved.fragment?.let { fragment ->
            sectionAnchorOffsets[spineIndex]?.get(fragment)?.let { anchorAbsolute ->
                (anchorAbsolute - (sectionStartOffsets[spineIndex] ?: 0L)).coerceAtLeast(0L)
            }
        } ?: 0L
        ReaderNavigationItem(
            title = entry.title.trim(),
            level = entry.level.coerceAtLeast(1),
            spineIndex = spineIndex,
            offset = offset,
        )
    }
    return ReaderNavigation(heading = navigation.heading, items = items)
}

/**
 * 이 내비게이션 항목의 제목이, 공백을 제외하면 문자 그대로 영어 또는 한국어로 "cover"라고
 * 읽히는지 여부 — 이 책들이 실제로 커버 항목에 쓰는 두 라벨이다.
 */
private fun String.isVisibleCoverLabel(): Boolean {
    val normalized = lowercase().replace(Regex("""\s+"""), "")
    return normalized == "cover" || normalized == "표지"
}

/**
 * 공백으로 구분되고 엔티티 인코딩된 속성 값(`epub:type`, 매니페스트 `properties`)을 소문자
 * 토큰들로 분리한다.
 */
private fun navTypeTokens(value: String?): Set<String> =
    value.orEmpty()
        .let(::decodeXmlEntities)
        .split(Regex("""\s+"""))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(String::lowercase)
        .toSet()

/** 문서를 텍스트 하나로 읽을 때 섹션들은 개행 문자 하나로 이어붙여진다. private이 아니라
 * internal인 이유: DocumentRepositoryImpl.importNextSections가 배치마다 같은 오프셋을 진행시킨다. */
internal const val SectionSeparatorLength = 1L

/** 임베드 이미지 하나의 디코딩된 크기 상한. [readBytesOrNull]이 강제한다. */
private const val MAX_EPUB_IMAGE_BYTES = 8L * 1024 * 1024

/**
 * [readHeaderBytesOrNull]이 커버가 아닌 이미지의 크기를 알아내기 위해 읽는 바이트 수.
 *
 * PNG, GIF, WebP는 처음 32바이트 안에 크기를 선언한다; JPEG의 SOF 마커는 `APP` 세그먼트들
 * 뒤 더 안쪽에 있으며, 임베드된 EXIF 썸네일만이 그것을 문제될 만큼 멀리 밀어낸다. 64
 * KiB이면 그 경우를 여유 있게, 딱 그만큼만 처리한다: 이 바이트 수는 책 안의 서로 다른
 * 이미지마다 한 번씩 읽히므로, 이 창(window)이 그림들의 크기를 알아내는 데 드는 비용
 * 전부다.
 */
private const val ImageHeaderSniffBytes = 64 * 1024

/** 챕터가 연결한 스타일시트를 찾기 위한 `<link>` 태그 매칭. */
private val StyleSheetLinkRegex = Regex("""(?is)<link\b[^>]*>""")

/** OPF 자체의 경로를 명시하는, EPUB 컨테이너 디스크립터의 고정된 스펙 지정 위치. */
private const val ContainerPath = "META-INF/container.xml"

/**
 * EPUB 2 NCX의 매니페스트 `media-type`. 스파인 자체의 `toc` 속성이 그것을 직접 지명하지 않을
 * 때 매니페스트 항목들 중에서 그것을 찾는 데 쓰인다.
 */
private const val NcxMediaType = "application/x-dtbncx+xml"
