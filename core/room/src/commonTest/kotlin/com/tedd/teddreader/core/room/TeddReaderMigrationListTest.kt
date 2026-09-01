package com.tedd.teddreader.core.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `RoomDatabase.Builder`는 마이그레이션 목록을 공개 접근자가 없는 비공개 필드에 보관합니다
 * (room3-runtime 3.0.0 소스에서 확인했으며 `Builder.migrationContainer`는 `private val`입니다).
 * 따라서 어느 플랫폼에서도 빌드된 데이터베이스에 나중에 "어떤 마이그레이션을 등록했는가"라고 물을 수 없습니다.
 * 실제 해결책은 구조적입니다. 두 `createTeddReaderDatabaseBuilder` 구현(android/ios)이 각자 목록을 작성하는 대신
 * 이 하나의 공유 목록에 `.addMigrations(*TeddReaderMigrationList.toTypedArray())`를 호출하므로 잘못될 수 있는
 * 목록도 하나뿐입니다. 이 테스트는 접근할 수 없는 빌더 내부 대신 공유 목록을 보호합니다.
 *
 * 또한 스키마 오류가 조용히 깨뜨릴 수 있는 유일한 요소인 마이그레이션 체인 자체를 보호합니다. 이전 버전에서
 * 업그레이드하는 설치본은 이 목록을 단계별로 따르므로 빠지거나 겹치는 링크가 있으면 새 데이터베이스만 여는
 * 테스트에서는 발생하지 않고 사용자 기기에서는 앱이 비정상 종료됩니다.
 */
class TeddReaderMigrationListTest {
    /** 아래 `currentDatabaseVersion`은 TeddReaderDatabase의 `@Database(version = ...)`와 수동으로 동기화합니다. */
    @Test
    fun migrationListCoversEveryVersionUpToTheCurrentDatabaseVersionWithNoGaps() {
        val currentDatabaseVersion = 9
        val versions = TeddReaderMigrationList.map { it.startVersion to it.endVersion }

        assertEquals((1 until currentDatabaseVersion).map { version -> version to version + 1 }, versions)
    }

    @Test
    fun migration8To9IsRegisteredExactlyOnceAndLast() {
        assertSame(TeddReaderMigration8To9, TeddReaderMigrationList.last())
        assertEquals(1, TeddReaderMigrationList.count { migration -> migration === TeddReaderMigration8To9 })
    }
}
