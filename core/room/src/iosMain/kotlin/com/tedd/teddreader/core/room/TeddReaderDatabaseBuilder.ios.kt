package com.tedd.teddreader.core.room

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import platform.Foundation.NSHomeDirectory

/**
 * iOS 데이터베이스 빌더입니다. 파일은 iOS가 백업하고 회수하지 않는 컨테이너 디렉터리인 `Documents/` 아래에
 * 저장됩니다. 독서 라이브러리는 캐시와 달리 기기 복원 후에도 유지되어야 합니다.
 *
 * Android와 같은 이유로 동일한 번들 드라이버와 동일한 [TeddReaderMigrationList]를 사용합니다.
 */
fun createTeddReaderDatabaseBuilder(): RoomDatabase.Builder<TeddReaderDatabase> =
    Room.databaseBuilder<TeddReaderDatabase>(
        name = "${NSHomeDirectory()}/Documents/$TeddReaderDatabaseName",
    )
        .addMigrations(*TeddReaderMigrationList.toTypedArray())
        .setDriver(BundledSQLiteDriver())
        .withWalSizeLimit()
