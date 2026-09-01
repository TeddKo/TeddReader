package com.tedd.teddreader.core.data.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [materializedDocumentFileName]의 계약을 고정한다: 문서가 구체화될 때 쓰이는 이름은 사본마다
 * 새로 지어내는 게 아니라 원본이 어디서 왔는지로부터 유도되어야 한다. 이 덕분에 같은 소스는
 * 반복된 임포트에서도 항상 같은 디스크 파일로 해석되고(중복된 책이 쌓이지 않음), 서로 다른
 * 소스는 표시 이름이 동일해도 절대 충돌하지 않으며, 원본 확장자는 그대로 유지되고(이후 형식
 * 감지가 이를 읽음), 공격자성 또는 파일시스템에 해로운 표시 이름 — 경로 구분자, `..` 트래버설,
 * 비-ASCII 텍스트 — 의 그 어떤 요소도 결과 이름에 도달할 수 없다. 이름은 항상 표시 이름 자체를
 * 변형한 것이 아니라 해시와 정제된 확장자로만 구성되기 때문이다.
 */
class MaterializedDocumentFileNameTest {
    /**
     * 이 파일이 존재하는 이유인 수정 사항을 지킨다: 같은 소스를 두 번째로 임포트하는 상황 —
     * 다른 앱이 "다른 앱으로 열기"를 통해 같은 책을 다시 넘겨줄 때 발생함 — 은 첫 임포트가 이미
     * 써 놓은 사본으로 해석되어야 한다. 그렇지 않으면 앱이 책을 다시 쓰고 두 번째로 임포트하게
     * 된다.
     */
    @Test
    fun theSameSourceAlwaysGetsTheSameName() {
        val first = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "dragon-raja.epub")
        val second = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "dragon-raja.epub")

        assertEquals(first, second)
    }

    /** 서로 무관한 두 문서가 같은 표시 이름을 공유해 서로의 사본을 덮어쓰는 일을 막는다. */
    @Test
    fun differentSourcesGetDifferentNamesEvenUnderTheSameDisplayName() {
        val first = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book.epub")
        val second = materializedDocumentFileName(sourceKey = "content://downloads/43", displayName = "book.epub")

        assertNotEquals(first, second)
    }

    /**
     * 생성된 이름이 여전히 원본 확장자로 끝나는지를 보장한다. 형식 감지의 폴백 경로가 파일명의
     * 확장자로부터 문서 형식을 추정하기 때문이다.
     */
    @Test
    fun theExtensionIsKeptBecauseFormatDetectionReadsIt() {
        assertTrue(
            materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book.epub").endsWith(".epub"),
        )
        assertTrue(
            materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "comic.cbz").endsWith(".cbz"),
        )
    }

    /**
     * 악의적이거나 단순히 낯선 표시 이름 — 경로 트래버설, 경로 구분자, 비-ASCII 이름 — 이 절대
     * 파일시스템에 도달하지 않도록 보장한다: 생성된 이름은 항상 16진수 SHA-1 해시와 정제된
     * 확장자로만 구성되며, 표시 이름 자체의 문자를 파생시킨 것이 아니다.
     */
    @Test
    fun aDisplayNameTheFilesystemWouldRefuseCannotReachTheName() {
        val name = materializedDocumentFileName(
            sourceKey = "content://downloads/42",
            displayName = "../../etc/passwd/한글 이름 (1).epub",
        )

        assertEquals("$name", name.substringAfterLast('/'), "a name must never carry a path separator")
        assertTrue(name.substringBefore('.').all { it in '0'..'9' || it in 'a'..'f' }, "the name is the hash: $name")
        assertTrue(name.endsWith(".epub"))
    }

    /**
     * 점이 없는 표시 이름이나, 실제 확장자라기엔 너무 길거나 영숫자가 아닌 "확장자"라도 크래시
     * 대신 마지막에 점이 붙지 않은 유효하고 비어 있지 않은 이름을 만들어내는지 보장한다.
     */
    @Test
    fun aDisplayNameWithNoUsableExtensionStillYieldsAName() {
        val name = materializedDocumentFileName(sourceKey = "content://downloads/42", displayName = "book")

        assertTrue(name.isNotBlank())
        assertTrue(!name.contains('.'))
    }
}
