package com.tedd.teddreader.core.room

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * iOS 데이터베이스 빌더입니다. 기존 설치의 독서 라이브러리를 유지하도록 `Documents/` 경로를 그대로 사용하되,
 * 앱이 `Documents/` 아래에 두는 모든 데이터가 iCloud나 기기 복원 데이터로 복제되지 않도록 `Documents/` 디렉터리 자체를
 * 백업 대상에서 제외합니다.
 *
 * 디렉터리 단위 제외 속성은 이후에 만들어지는 SQLite WAL, SHM 파일과 가져온 도서 사본, 같은 디렉터리의
 * DataStore 환경설정 파일까지 모두 포함하므로 파일별 처리가 필요 없습니다. 이렇게 해야 복원 뒤에 데이터베이스는 없고
 * 도서 파일만 남아 접근할 수 없는 고아 데이터가 되는 상황을 막을 수 있습니다. `Documents/` 밖의 경로(`Library/` 등)는
 * 이 정책의 범위가 아닙니다. 속성 설정 실패는 데이터베이스 사용을 막지 않고 경고만 기록합니다.
 *
 * Android와 같은 이유로 동일한 번들 드라이버와 동일한 [TeddReaderMigrationList]를 사용합니다.
 *
 * @return 기존 경로와 마이그레이션을 유지하면서 `Documents/`가 iCloud 백업에서 제외된 빌더입니다.
 */
fun createTeddReaderDatabaseBuilder(): RoomDatabase.Builder<TeddReaderDatabase> {
    val documentsPath = "${NSHomeDirectory()}/Documents"
    excludeDirectoryFromBackup(documentsPath)

    return Room.databaseBuilder<TeddReaderDatabase>(name = "$documentsPath/$TeddReaderDatabaseName")
        .addMigrations(*TeddReaderMigrationList.toTypedArray())
        .setDriver(BundledSQLiteDriver())
        .withWalSizeLimit()
}

/**
 * 디렉터리에 `NSURLIsExcludedFromBackupKey`를 설정해 그 안의 기존 파일과 이후 생성될 파일 전체를 iCloud 백업
 * 대상에서 제외합니다. 실패는 호출자의 초기화를 중단시키지 않고 경고로만 남깁니다.
 *
 * @param directoryPath 백업에서 제외할 디렉터리의 절대 경로입니다.
 * @return 속성 설정에 성공하면 true, 실패해 경고를 남겼다면 false입니다.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun excludeDirectoryFromBackup(directoryPath: String): Boolean {
    val wasExcluded = NSURL.fileURLWithPath(directoryPath, isDirectory = true).setResourceValue(
        value = true,
        forKey = NSURLIsExcludedFromBackupKey,
        error = null,
    )
    if (!wasExcluded) {
        BackupExclusionLogger.w { "iCloud 백업 제외 속성을 설정하지 못했습니다: $directoryPath" }
    }
    return wasExcluded
}

/** 파일 속성 오류를 데이터베이스 초기화 실패와 분리해 기록하는 로거입니다. */
private val BackupExclusionLogger = Logger.withTag("RoomBackup")
