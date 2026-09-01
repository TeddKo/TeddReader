package com.tedd.teddreader.feature.reader.impl.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** visual 페이지 비트맵 캐시 식별자가 문서나 페이지 경계를 넘지 않고 안정적으로 유지됨을 검증한다. */
class ImagePageSurfaceTest {
    /** 하나의 CBZ 페이지를 중복 구성해도 같은 디코딩된 비트맵을 가리켜야 한다. */
    @Test
    fun visualPageMemoryCacheKeyIsStableForOneDocumentPage() {
        assertEquals(
            visualPageMemoryCacheKey("file:///library/comic.cbz", 3),
            visualPageMemoryCacheKey("file:///library/comic.cbz", 3),
        )
    }

    /** 서로 다른 CBZ 문서에서 페이지 번호가 같더라도 디코딩된 비트맵을 절대 공유해서는 안 된다. */
    @Test
    fun visualPageMemoryCacheKeySeparatesDocuments() {
        assertNotEquals(
            visualPageMemoryCacheKey("file:///library/first.cbz", 3),
            visualPageMemoryCacheKey("file:///library/second.cbz", 3),
        )
    }

    /** 하나의 CBZ 안에서 인접한 페이지는 디코딩된 비트맵을 절대 공유해서는 안 된다. */
    @Test
    fun visualPageMemoryCacheKeySeparatesPages() {
        assertNotEquals(
            visualPageMemoryCacheKey("file:///library/comic.cbz", 3),
            visualPageMemoryCacheKey("file:///library/comic.cbz", 4),
        )
    }

    /** 문서 식별자가 없으면 ByteArray 데이터는 Coil의 메모리 캐시 밖에 머무른다. */
    @Test
    fun visualPageMemoryCacheKeyRequiresDocument() {
        assertEquals(null, visualPageMemoryCacheKey(null, 3))
        assertEquals(null, visualPageMemoryCacheKey("", 3))
    }
}
