package com.tedd.teddreader.core.datastore

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 실제 Okio 저장소를 사용하는 [ReaderPreferencesDataSource] 생성 경로가 손상된 설정 파일에서도 앱 시작을
 * 계속할 수 있는지 검증한다.
 */
class ReaderPreferencesDataSourceTest {
    /** 파싱할 수 없는 기존 파일을 기본 환경설정으로 교체하고 첫 읽기를 정상 완료하는지 검증한다. */
    @Test
    fun corruptedFileIsReplacedWithDefaultPreferences() = runTest {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "teddreader-corrupted-preferences-${Random.nextLong().toString(16)}.json"
        try {
            FileSystem.SYSTEM.write(path) {
                writeUtf8("not-json")
            }
            val dataStore = createReaderPreferencesDataStore(
                fileSystem = FileSystem.SYSTEM,
                producePath = { path },
            )

            assertEquals(ReaderPreferences(), dataStore.data.first())
        } finally {
            FileSystem.SYSTEM.delete(path, mustExist = false)
        }
    }
}
