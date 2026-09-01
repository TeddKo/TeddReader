package com.tedd.teddreader.core.data.pagination

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Compose의 Skia 텍스트 스택은 스레드 세이프하지 않으므로(정확한 공유 맵은 expect 선언 참고), iOS에서는
 * 실제 측정이 모두 메인 디스패처에서 실행되며, 스레드를 공유함으로써 페이지 서피스 자체의 레이아웃과
 * 직렬화된다. `.immediate` 대신 그냥 [Dispatchers.Main]을 쓰는 이유: 측정 배치는 대기 중인 프레임을
 * 선점하지 않고 그 뒤에 줄 서야 하기 때문이다.
 */
internal actual val ReaderPageMeasureDispatcher: CoroutineDispatcher = Dispatchers.Main
