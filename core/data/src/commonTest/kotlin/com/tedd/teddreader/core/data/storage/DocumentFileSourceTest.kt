package com.tedd.teddreader.core.data.storage

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 문서 삭제가 플랫폼의 실체화 디렉터리의 직계 자식만 허용하는지 검증한다. */
class DocumentFileSourceTest {
    /** 직계 자식은 프로덕션 소스가 삭제해도 되는, 앱이 소유한 실체화 파일이다. */
    @Test
    fun directChildBelongsToMaterializedDirectory() {
        assertTrue(
            isDirectChildOf(
                path = "/app/documents/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** 앱 디렉터리 옆에 있는 외부 소스는 그 서가(shelf) 행과 함께 결코 삭제되어서는 안 된다. */
    @Test
    fun siblingDirectoryDoesNotBelongToMaterializedDirectory() {
        assertFalse(
            isDirectChildOf(
                path = "/external/documents/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** 중첩된 파일은 한 단계짜리 실체화 레이아웃 밖에 있으며 건드려지지 않아야 한다. */
    @Test
    fun nestedFileDoesNotBelongToMaterializedDirectory() {
        assertFalse(
            isDirectChildOf(
                path = "/app/documents/nested/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** 정규화는 트래버설 세그먼트가 소유 디렉터리 검사를 벗어나지 못하도록 막아야 한다. */
    @Test
    fun traversalPathDoesNotBelongToMaterializedDirectory() {
        assertFalse(
            isDirectChildOf(
                path = "/app/documents/../external/book.cbz".toPath(),
                directory = "/app/documents".toPath(),
            ),
        )
    }

    /** 이전 앱 컨테이너 UUID도 여전히 같은, 앱이 소유한 Documents 디렉터리를 식별한다. */
    @Test
    fun relocatedContainerDirectChildBelongsToMaterializedDirectory() {
        assertTrue(
            isDirectChildOfCurrentOrRelocatedDirectory(
                path = "/containers/old-uuid/Documents/legacy-book-2.epub".toPath(),
                currentDirectory = "/containers/current-uuid/Documents".toPath(),
            ),
        )
    }

    /** 앱 컨테이너 루트 밖에 있는, 이름이 같은 외부 디렉터리는 건드려지지 않아야 한다. */
    @Test
    fun unrelatedDocumentsDirectoryDoesNotBelongToRelocatedContainer() {
        assertFalse(
            isDirectChildOfCurrentOrRelocatedDirectory(
                path = "/external/Documents/legacy-book-2.epub".toPath(),
                currentDirectory = "/containers/current-uuid/Documents".toPath(),
            ),
        )
    }
}
