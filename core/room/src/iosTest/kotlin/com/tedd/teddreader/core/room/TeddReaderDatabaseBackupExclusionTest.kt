package com.tedd.teddreader.core.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * 빌더가 `Documents/`에 사용하는 디렉터리 단위 백업 제외가 실제 파일 URL 속성으로 남는지 검증합니다.
 *
 * 프로덕션 `Documents/`를 건드리지 않도록 임시 디렉터리에 같은 [excludeDirectoryFromBackup]을 적용합니다.
 */
@OptIn(ExperimentalForeignApi::class)
class TeddReaderDatabaseBackupExclusionTest {
    /** 디렉터리가 `NSURLIsExcludedFromBackupKey`를 갖는지 검증합니다. */
    @Test
    fun directoryIsExcludedFromBackup() {
        val fileManager = NSFileManager.defaultManager
        val directoryPath = "${NSTemporaryDirectory()}/teddreader-backup-exclusion-test"

        fileManager.removeItemAtPath(directoryPath, error = null)
        assertTrue(
            fileManager.createDirectoryAtPath(
                path = directoryPath,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        )

        try {
            assertTrue(excludeDirectoryFromBackup(directoryPath))

            val resourceValues = NSURL.fileURLWithPath(directoryPath, isDirectory = true).resourceValuesForKeys(
                keys = listOf(NSURLIsExcludedFromBackupKey),
                error = null,
            )
            assertEquals(true, resourceValues?.get(NSURLIsExcludedFromBackupKey))
        } finally {
            fileManager.removeItemAtPath(directoryPath, error = null)
        }
    }
}
