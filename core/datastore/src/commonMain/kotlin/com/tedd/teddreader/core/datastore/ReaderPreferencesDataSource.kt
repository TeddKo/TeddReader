package com.tedd.teddreader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path

/**
 * 저장된 환경설정에 대한 앱의 읽기 및 쓰기 진입점이며 환경설정마다 호출 하나를 제공한다.
 *
 * 각 쓰기는 키 기반 쓰기 대신 전체 파일에 `updateData`를 적용한다. DataStore가 이 갱신을 직렬화하므로
 * 두 화면이 서로 다른 환경설정을 동시에 바꿔도 한쪽이 전체 객체의 오래된 복사본으로 다른 쪽을
 * 덮어쓸 수 없다.
 *
 * @property preferences 디스크의 현재 값에서 시작해 저장된 환경설정과 이후 모든 변경을 방출한다.
 */
class ReaderPreferencesDataSource(
    private val dataStore: DataStore<ReaderPreferences>,
) {
    val preferences: Flow<ReaderPreferences> = dataStore.data

    /** @param style 저장할 읽기 스타일. */
    suspend fun updateStyle(style: ReaderStyle) {
        dataStore.updateData { it.copy(style = style) }
    }

    /** @param pageTurnMode 저장할 페이지 넘김 방향. */
    suspend fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        dataStore.updateData { it.copy(pageTurnMode = pageTurnMode) }
    }

    /** @param pageAnimation 저장할 페이지 넘김 애니메이션. */
    suspend fun updatePageAnimation(pageAnimation: PageAnimation) {
        dataStore.updateData { it.copy(pageAnimation = pageAnimation) }
    }

    /** @param autoScrollConfig 저장할 자동 스크롤 구성. 속도는 쓸 때 범위 안으로 제한된다. */
    suspend fun updateAutoScrollConfig(autoScrollConfig: AutoScrollConfig) {
        dataStore.updateData { it.copy(autoScrollConfig = autoScrollConfig) }
    }

    /** @param appLanguage 저장할 앱 언어. */
    suspend fun updateAppLanguage(appLanguage: AppLanguage) {
        dataStore.updateData { it.copy(appLanguage = appLanguage) }
    }
}

/**
 * 호출자가 제공한 경로를 사용해 플랫폼 빌더가 감싸는 환경설정 저장소를 만든다.
 *
 * 파일 시스템과 경로를 모두 매개변수로 받으므로 테스트는 저장소가 임시 디렉터리를 가리키게 할 수
 * 있고, 이 함수는 두 플랫폼의 공통 코드로 유지된다.
 *
 * @param fileSystem 읽기와 쓰기에 사용할 파일 시스템.
 * @param producePath 환경설정 파일이 있는 경로. 플랫폼이 자체 디렉터리를 조회할 수 있도록
 * 지연 결정된다.
 * @return [ReaderPreferencesSerializer]를 사용하는 저장소.
 */
fun createReaderPreferencesDataStore(
    fileSystem: FileSystem,
    producePath: () -> Path,
): DataStore<ReaderPreferences> = DataStoreFactory.create(
    storage = OkioStorage(
        fileSystem = fileSystem,
        serializer = ReaderPreferencesSerializer,
        producePath = producePath,
    ),
)
