package com.tedd.teddreader.core.data.pagination

import kotlinx.coroutines.CoroutineDispatcher

/**
 * 실제 페이지 나눔 측정이 실행되어야 하는 디스패처.
 *
 * [com.tedd.teddreader.core.common.model.ReaderPageBreaker]는 UI 프레임워크 자신의 텍스트 스택으로
 * 텍스트를 배치하며, 그 스택이 두 번째 스레드를 견디는지는 저장소가 결정할 일이 아니라 *플랫폼* 차원의
 * 사실이다:
 *
 * - Android에서는 플랫폼 텍스트 레이아웃을 메인 스레드 밖에서 실행해도 안전하며, 페이지네이션은 일부러
 *   백그라운드 디스패처에서 측정해 책 전체 재페이지네이션이 UI를 절대 굶기지 않게 한다.
 * - iOS에서는 Compose의 Skia 텍스트 스택이 모든 측정기에 걸쳐 동기화되지 않은 프로세스 전역 상태를
 *   공유한다 — `ParagraphBuilder.skiko.kt`의 파일 레벨 `skTextStylesCache`는 평범한 `HashMap`이고, 그
 *   `getOrPut`은 `entries.removeAll`로 정리한다. 메인 스레드가 화면에 보이는 페이지를 배치하는 동안
 *   백그라운드 스레드에서 측정하면 둘이 같은 맵을 두고 경합해 `ConcurrentModificationException`으로
 *   비정상 종료됐다. 그 스택이 상류에서 스레드 안전해지기 전까지는, 모든 측정이 그리기와 메인 스레드를
 *   공유해야 하며, 이는 구조적으로 그것들을 직렬화한다.
 */
internal expect val ReaderPageMeasureDispatcher: CoroutineDispatcher
