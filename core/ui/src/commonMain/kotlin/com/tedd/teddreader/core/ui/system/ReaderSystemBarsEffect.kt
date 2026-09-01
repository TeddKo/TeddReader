package com.tedd.teddreader.core.ui.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 앱 전역 테마 색상과 그에 맞는 아이콘 대비를 플랫폼 시스템 바에 적용한다. 이것은 앱 루트에서 한
 * 번만 컴포즈되어, 홈, 검색, 설정, 문서 정보, 리더가 모두 같은 저장된 리더 테마 업데이트를 받는다.
 *
 * @param backgroundColor 현재 전역 테마의 불투명한 페이지 색상.
 */
@Composable
expect fun SystemBarsThemeEffect(backgroundColor: Color)

/**
 * 리더 고유의 창 동작 — 몰입형 가시성과 화면 꺼짐 방지 — 만을 소유한다. 시스템 바 색상과 아이콘
 * 대비는 계속 앱 루트의 책임으로 남는다.
 *
 * @param visible 시스템 상태/내비게이션 바를 표시할지 여부.
 * @param keepScreenOn 읽는 동안 기기 화면이 계속 켜져 있어야 하는지 여부.
 */
@Composable
expect fun ReaderSystemBarsEffect(
    visible: Boolean,
    keepScreenOn: Boolean,
)
