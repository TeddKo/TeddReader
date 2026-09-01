package com.tedd.teddreader.core.data.pagination

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 안드로이드 텍스트 레이아웃(Compose의 TextMeasurer 아래 있는 StaticLayout)은 메인 스레드
 * 밖에서도 안전하므로, 페이지네이션은 늘 그래왔듯 백그라운드에서 계속 측정한다 — 메인
 * 스레드에서의 책 전체 재페이지네이션은 UI를 굶주리게 했을 것이다(애초에 측정을 메인
 * 스레드에서 빼낸 이유다).
 */
internal actual val ReaderPageMeasureDispatcher: CoroutineDispatcher = Dispatchers.Default
