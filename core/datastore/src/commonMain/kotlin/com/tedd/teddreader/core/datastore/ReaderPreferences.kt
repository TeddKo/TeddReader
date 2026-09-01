package com.tedd.teddreader.core.datastore

import com.tedd.teddreader.core.common.model.AppLanguage
import com.tedd.teddreader.core.common.model.AutoScrollConfig
import com.tedd.teddreader.core.common.model.PageAnimation
import com.tedd.teddreader.core.common.model.PageTurnMode
import com.tedd.teddreader.core.common.model.ReaderStyle
import kotlinx.serialization.Serializable

/**
 * 읽기 환경설정을 디스크의 JSON 파일에 저장된 형태 그대로 나타낸다.
 *
 * 도메인의 `ReaderSettings`가 아닌 전용 저장 타입이므로 마이그레이션 없이 두 형식을 달리할 수 있다.
 * 도메인에서 필드 이름을 바꿔도 독자의 디스크에 이미 있는 키 이름은 바뀌지 않으며, 디스크에서 제거한
 * 키를 도메인에 유지할 필요도 없다. 직렬 변환기의 `ignoreUnknownKeys`는 반대 방향도 안전하게 만들어
 * 새 빌드가 작성한 파일도 여기서 읽을 수 있게 한다.
 *
 * 기본값은 새 설치가 처음 사용하는 값이며 누락된 키가 대체되는 값이다.
 *
 * @property style 독자가 본문을 그릴 때 사용하는 글꼴 종류와 페이지 색상.
 * @property pageTurnMode 페이지를 넘기는 방향. 저장된 `CONTINUOUS`는 읽을 때 정상화된다.
 * @property pageAnimation 페이지 넘김을 애니메이션하는 페이저. 레거시 값은 읽을 때 정상화된다.
 * @property autoScrollConfig 자동 스크롤의 활성화 상태, 단위, 속도. 속도는 읽을 때 범위 안으로 제한된다.
 * @property appLanguage 앱 자체 문자열에 사용하는 언어.
 */
@Serializable
data class ReaderPreferences(
    val style: ReaderStyle = ReaderStyle(),
    val pageTurnMode: PageTurnMode = PageTurnMode.HORIZONTAL,
    val pageAnimation: PageAnimation = PageAnimation.SLIDE,
    val autoScrollConfig: AutoScrollConfig = AutoScrollConfig(),
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
)
