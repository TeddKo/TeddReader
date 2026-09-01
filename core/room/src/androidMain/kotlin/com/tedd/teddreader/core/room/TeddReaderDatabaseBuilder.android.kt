package com.tedd.teddreader.core.room

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/**
 * 애플리케이션 컨텍스트에서 열려 어떤 Activity보다 오래 유지되는 Android 데이터베이스 빌더입니다.
 *
 * 번들 SQLite 드라이버 사용은 의도적인 선택입니다. 앱과 함께 단일 SQLite 빌드를 배포하므로 최신 기기에서
 * 작동하는 쿼리는 오래된 기기에서도 작동하며, 매개변수와 표현식 제한도 플랫폼이 아닌 번들 라이브러리를
 * 따릅니다. 마이그레이션은 여기에 나열하지 않고 [TeddReaderMigrationList]에서 가져오므로 두 플랫폼의 구성이
 * 서로 달라질 수 없습니다.
 */
fun createTeddReaderDatabaseBuilder(
    context: Context,
): RoomDatabase.Builder<TeddReaderDatabase> =
    Room.databaseBuilder<TeddReaderDatabase>(
        context = context.applicationContext,
        name = TeddReaderDatabaseName,
    )
        .addMigrations(*TeddReaderMigrationList.toTypedArray())
        .setDriver(BundledSQLiteDriver())
        .withWalSizeLimit()
