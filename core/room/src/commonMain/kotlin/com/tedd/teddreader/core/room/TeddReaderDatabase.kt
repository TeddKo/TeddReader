package com.tedd.teddreader.core.room

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import com.tedd.teddreader.core.room.dao.BookmarkDao
import com.tedd.teddreader.core.room.dao.DocumentDao
import com.tedd.teddreader.core.room.dao.PageLayoutDao
import com.tedd.teddreader.core.room.dao.ReadingProgressDao
import com.tedd.teddreader.core.room.dao.ReadingSessionDao
import com.tedd.teddreader.core.room.dao.SearchIndexDao
import com.tedd.teddreader.core.room.entity.BookmarkEntity
import com.tedd.teddreader.core.room.entity.DocumentEntity
import com.tedd.teddreader.core.room.entity.PageLayoutEntity
import com.tedd.teddreader.core.room.entity.ReadingProgressEntity
import com.tedd.teddreader.core.room.entity.ReadingSessionEntity
import com.tedd.teddreader.core.room.entity.SearchIndexEntity

/**
 * 라이브러리와 여기서 파생된 모든 데이터를 보관하는 앱의 단일 SQLite 데이터베이스입니다.
 *
 * 여섯 개 테이블은 각각 문서 목록, 문서별 독서 위치, 저장한 위치, 독서 시간, 검색 가능한 텍스트, 측정한 페이지
 * 레이아웃이라는 하나의 목적을 담당합니다. 모두 문서를 키로 사용하고 문서와 함께 삭제되므로 여러 데이터베이스가
 * 아닌 하나의 데이터베이스에 둡니다.
 *
 * 이곳의 마이그레이션은 직접 작성하며 생성된 스키마 덤프 대신 체인을 검사하는
 * TeddReaderMigrationListTest로 검증하므로 `exportSchema = false`입니다.
 *
 * 두 플랫폼 모두 생성 코드(`@ConstructedBy`)를 통해 이 데이터베이스를 구성하고 번들 SQLite 드라이버로 엽니다.
 * 플랫폼의 `createTeddReaderDatabaseBuilder`를 참고하십시오. 따라서 Android와 iOS는 OS가 제공하는 빌드 대신
 * 같은 SQLite 빌드와 같은 마이그레이션 목록을 실행합니다.
 */
@Database(
    entities = [
        DocumentEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        SearchIndexEntity::class,
        PageLayoutEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
@ConstructedBy(TeddReaderDatabaseConstructor::class)
abstract class TeddReaderDatabase : RoomDatabase() {
    /** 존재하는 문서와 정렬 순서를 담는 라이브러리 테이블입니다. */
    abstract fun documentDao(): DocumentDao
    /** 문서마다 하나씩 현재 읽고 있는 위치를 담습니다. */
    abstract fun readingProgressDao(): ReadingProgressDao
    /** 문서별로 저장한 위치를 담습니다. */
    abstract fun bookmarkDao(): BookmarkDao
    /** 독서 세션과 합산된 활성 시간을 담습니다. 아직 이 테이블에 쓰는 코드는 없습니다. */
    abstract fun readingSessionDao(): ReadingSessionDao
    /** 리더의 원본이자 검색 인덱스인 저장된 문서 텍스트입니다. */
    abstract fun searchIndexDao(): SearchIndexDao
    /** 문서, 글자 설정, 뷰포트를 키로 하는 캐시된 페이지 측정값입니다. */
    abstract fun pageLayoutDao(): PageLayoutDao
}

/**
 * Room이 플랫폼마다 실제 객체를 생성하므로 expect 선언에 여기서 보이는 actual이 없으며, 이로 인한 컴파일러
 * 경고를 억제합니다.
 */
@Suppress("KotlinNoActualForExpect")
expect object TeddReaderDatabaseConstructor : RoomDatabaseConstructor<TeddReaderDatabase> {
    /**
     * 위의 `@Database`/`@ConstructedBy` 어노테이션으로부터 플랫폼별로 생성되는 Room 자체 KSP 구현입니다.
     * 여기에는 읽을 수 있는 수동 작성 본문이 없습니다. 생성된 데이터베이스 클래스의 기본 인스턴스만 만들며,
     * 실제 파일에 연결해 열고 SQLite 드라이버를 배선하고 마이그레이션 체인을 구동하는 것은 플랫폼의
     * `createTeddReaderDatabaseBuilder`입니다. 클래스 문서를 참고하십시오.
     *
     * @return 아직 파일에 연결해 열거나 마이그레이션하지 않은 새 [TeddReaderDatabase] 인스턴스입니다.
     */
    override fun initialize(): TeddReaderDatabase
}
