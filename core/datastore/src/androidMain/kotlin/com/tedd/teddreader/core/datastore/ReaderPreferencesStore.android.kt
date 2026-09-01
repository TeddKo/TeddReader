package com.tedd.teddreader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Android에서 앱의 비공개 파일 디렉터리 아래에 환경설정 저장소를 연다.
 *
 * @param context 어떤 컨텍스트든 허용한다. 경로는 애플리케이션 컨텍스트에서 결정되므로 저장소는
 * 자신을 생성한 액티비티보다 오래 유지된다.
 * @return 앱이 환경설정을 읽고 쓰는 단일 저장소.
 */
fun createReaderPreferencesDataStore(
    context: Context,
): DataStore<ReaderPreferences> = createReaderPreferencesDataStore(
    fileSystem = FileSystem.SYSTEM,
) {
    context.filesDir.resolve(ReaderPreferencesFileName).absolutePath.toPath()
}
