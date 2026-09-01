package com.tedd.teddreader.core.datastore

import androidx.datastore.core.DataStore
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/**
 * iOS에서 환경설정 저장소를 `Documents/` 아래에 연다. 이 컨테이너 디렉터리는 iOS가 백업하고
 * 회수하지 않으므로, 독자의 설정은 캐시로 취급되지 않고 복원 후에도 유지된다.
 *
 * @return 앱이 환경설정을 읽고 쓰는 단일 저장소.
 */
fun createReaderPreferencesDataStore(): DataStore<ReaderPreferences> =
    createReaderPreferencesDataStore(
        fileSystem = FileSystem.SYSTEM,
    ) {
        "${NSHomeDirectory()}/Documents/$ReaderPreferencesFileName".toPath()
    }
