package com.tedd.teddreader.core.data.parser

import com.tedd.teddreader.core.common.model.DocumentId
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.FileSystem

/** 실제 ZIP을 사용해 CBZ 탐색 상한, 배치 예산, 임시파일 수명과 페이지 읽기를 검증한다. */
class ComicBookDocumentParserArchiveTest {
    /** 메타데이터를 제외한 이미지가 표지 우선 자연 순서로 읽힌다. */
    @Test
    fun parsesAndReadsNaturallyOrderedImageEntries() {
        val cover = byteArrayOf(1)
        val page2 = byteArrayOf(2)
        val page10 = byteArrayOf(10)
        val bytes = comicZip(
            "page10.jpg" to page10,
            "cover.jpg" to cover,
            "page2.png" to page2,
            "notes.txt" to byteArrayOf(99),
        )
        val parser = ComicBookDocumentParser()

        val document = parser.parse(DocumentId("comic"), "Comic", bytes)
        val pages = parser.pageImageBytes(bytes, setOf(0, 1, 2))

        assertEquals(3, document.pageCount)
        assertContentEquals(cover, pages[0])
        assertContentEquals(page2, pages[1])
        assertContentEquals(page10, pages[2])
    }

    /** 디스크 경로로 연 아카이브도 바이트 오버로드와 같은 읽기 순서를 사용한다. */
    @Test
    fun parsesAndReadsNaturallyOrderedImageEntriesFromPath() {
        val cover = byteArrayOf(1)
        val page2 = byteArrayOf(2)
        val page10 = byteArrayOf(10)
        val bytes = comicZip(
            "page10.jpg" to page10,
            "cover.jpg" to cover,
            "page2.png" to page2,
        )
        val parser = ComicBookDocumentParser()
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "comic-parser-${Random.nextLong().toString(16)}.cbz"
        FileSystem.SYSTEM.write(path) { write(bytes) }

        try {
            val document = parser.parse(DocumentId("comic"), "Comic", path)
            val pages = parser.pageImageBytes(path, setOf(0, 1, 2))

            assertEquals(3, document.pageCount)
            assertContentEquals(cover, pages[0])
            assertContentEquals(page2, pages[1])
            assertContentEquals(page10, pages[2])
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 지원 페이지 수가 상한을 넘는 아카이브는 페이지 경로 목록을 보유하기 전에 거부된다. */
    @Test
    fun rejectsArchiveWithTooManyPages() {
        val entries = Array(MaxComicPages + 1) { index -> "page-$index.jpg" to byteArrayOf() }
        val parser = ComicBookDocumentParser()

        assertFailsWith<IllegalArgumentException> {
            parser.parse(DocumentId("comic"), "Comic", comicZip(*entries))
        }
    }

    /** 지나치게 긴 엔트리 경로는 정렬과 저장 전에 거부된다. */
    @Test
    fun rejectsArchiveWithOverlongEntryPath() {
        val name = "a".repeat(MaxComicEntryPathLength + 1) + ".jpg"
        val parser = ComicBookDocumentParser()

        assertFailsWith<IllegalArgumentException> {
            parser.parse(DocumentId("comic"), "Comic", comicZip(name to byteArrayOf(1)))
        }
    }

    /** 이미지가 아닌 항목을 포함해 전체 ZIP 엔트리 수 상한을 넘으면 탐색이 중단된다. */
    @Test
    fun rejectsArchiveWithTooManyEntries() {
        val entries = Array(MaxComicArchiveEntries + 1) { index ->
            if (index == 0) "page.jpg" to byteArrayOf(1) else "metadata-$index.txt" to byteArrayOf()
        }
        val parser = ComicBookDocumentParser()

        assertFailsWith<IllegalArgumentException> {
            parser.parse(DocumentId("comic"), "Comic", comicZip(*entries))
        }
    }

    /** 한 배치가 허용된 인덱스 수보다 많으면 아카이브 읽기를 시작하지 않고 거부된다. */
    @Test
    fun rejectsPageBatchWithTooManyIndexes() {
        val archive = ComicArchive(FileSystem.SYSTEM, emptyList())

        assertFailsWith<IllegalArgumentException> {
            archive.pageImageBytes((0..MaxComicPageRequestCount).toSet())
        }
    }

    /** 여러 페이지의 합이 과거의 배치 예산을 넘어도 요청한 유효 페이지는 모두 반환된다. */
    @Test
    fun returnsAllRequestedPagesRegardlessOfAggregateSize() {
        val pageSize = 12 * 1024 * 1024
        val bytes = comicZip(
            "page-1.jpg" to ByteArray(pageSize),
            "page-2.jpg" to ByteArray(pageSize),
            "page-3.jpg" to ByteArray(pageSize),
        )
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "comic-budget-${Random.nextLong().toString(16)}.cbz"
        FileSystem.SYSTEM.write(path) { write(bytes) }

        try {
            val pages = ComicBookDocumentParser().openArchive(path).pageImageBytes(setOf(0, 1, 2))

            assertEquals(setOf(0, 1, 2), pages.keys)
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }

    /** 디렉터리와 Mac 메타데이터 항목은 엔트리 수 상한에 셈하지 않는다. */
    @Test
    fun entryLimitIgnoresDirectoriesAndMacMetadata() {
        val entries = arrayOf("page.jpg" to byteArrayOf(1)) +
            Array(MaxComicArchiveEntries) { index -> "dir-$index/" to byteArrayOf() } +
            Array(MaxComicArchiveEntries) { index -> "__MACOSX/._page-$index.jpg" to byteArrayOf() }

        val document = ComicBookDocumentParser().parse(DocumentId("comic"), "Comic", comicZip(*entries))

        assertEquals(1, document.pageCount)
    }

    /** 임시 파일을 사용하는 작업이 실패해도 호출이 소유한 파일은 남지 않는다. */
    @Test
    fun temporaryComicFileIsDeletedWhenBlockFails() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "comic-cleanup-${Random.nextLong().toString(16)}.cbz"

        assertFailsWith<IllegalStateException> {
            withTemporaryComicFile(FileSystem.SYSTEM, path, byteArrayOf(1)) {
                assertTrue(FileSystem.SYSTEM.exists(path))
                error("stop")
            }
        }

        assertFalse(FileSystem.SYSTEM.exists(path))
    }
}

/**
 * [entries]를 테스트용 ZIP 바이트로 직렬화한다.
 *
 * @param entries ZIP 엔트리 이름과 압축 전 바이트의 쌍.
 * @return 입력 순서를 보존한 ZIP 바이트.
 */
private fun comicZip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return output.toByteArray()
}
