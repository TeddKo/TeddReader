package com.tedd.teddreader.core.room.di

import com.tedd.teddreader.core.room.TeddReaderDatabase
import com.tedd.teddreader.core.room.dao.BookmarkDao
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.ReadingProgressDao
import com.tedd.teddreader.core.room.dao.ReadingSessionDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * `core:room`이 그래프에 공급하는 DAO들의 진입점이다.
 *
 * [TeddReaderDatabase] 자체는 여기서 만들지 않는다 — 플랫폼별 파일 경로와 SQLite 드라이버 배선이
 * 필요해 앱의 플랫폼 모듈이 대신 공급하며, 이 모듈은 그렇게 주입받은 데이터베이스 인스턴스에서
 * 각 DAO를 꺼내는 여섯 개의 `@Single` 공급자 함수만 담는다. `@ComponentScan`을 붙이지 않은 이유는
 * 스캔할 애노테이션 붙은 클래스가 없고, 이 여섯 함수가 이 모듈이 등록해야 할 바인딩의 전부이기
 * 때문이다.
 *
 * 여기서 나오는 [DocumentDao], [ReadingProgressDao], [BookmarkDao], [ReadingSessionDao],
 * [SearchIndexDao], [PageLayoutDao] 바인딩은 `core:data`의 리포지토리 구현들이 생성자로
 * 주입받아 사용한다.
 */
@Module
class RoomModule {
    /** [TeddReaderDatabase]에서 라이브러리 테이블에 접근하는 DAO를 꺼낸다. */
    @Single
    fun documentDao(database: TeddReaderDatabase): DocumentDao = database.documentDao()

    /** [TeddReaderDatabase]에서 독서 진행 위치 테이블에 접근하는 DAO를 꺼낸다. */
    @Single
    fun readingProgressDao(database: TeddReaderDatabase): ReadingProgressDao = database.readingProgressDao()

    /** [TeddReaderDatabase]에서 저장한 위치 테이블에 접근하는 DAO를 꺼낸다. */
    @Single
    fun bookmarkDao(database: TeddReaderDatabase): BookmarkDao = database.bookmarkDao()

    /** [TeddReaderDatabase]에서 독서 세션 테이블에 접근하는 DAO를 꺼낸다. */
    @Single
    fun readingSessionDao(database: TeddReaderDatabase): ReadingSessionDao = database.readingSessionDao()

    /** [TeddReaderDatabase]에서 검색 인덱스 테이블에 접근하는 DAO를 꺼낸다. */
    @Single
    fun searchIndexDao(database: TeddReaderDatabase): SearchIndexDao = database.searchIndexDao()

    /** [TeddReaderDatabase]에서 측정된 페이지 레이아웃 테이블에 접근하는 DAO를 꺼낸다. */
    @Single
    fun pageLayoutDao(database: TeddReaderDatabase): PageLayoutDao = database.pageLayoutDao()
}
