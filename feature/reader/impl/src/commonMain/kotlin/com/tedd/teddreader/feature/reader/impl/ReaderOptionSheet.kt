package com.tedd.teddreader.feature.reader.impl

/**
 * 현재 어떤 리더 설정 화면이 열려 있는지 식별하며, 아무것도 열려 있지 않으면
 * [ReaderUiState.activeSheet][com.tedd.teddreader.feature.reader.impl.ReaderUiState]에서 `null`이다.
 * 공용 enum에 화면마다 boolean 플래그를 하나씩 두는 대신 빈 마커들의 sealed 계층으로 두어,
 * [ReaderUiState][com.tedd.teddreader.feature.reader.impl.ReaderUiState]가 "무엇이 열려 있는지"를 담는
 * 필드를 정확히 하나만 가지고 `ReaderScreen`의 분기 처리가 이를 포괄하는 `when`이 되도록 한다 — 새 설정
 * 화면을 추가한다는 것은 여기에 멤버 하나, 저기에 분기 하나를 추가하는 것이지, 첫 번째 플래그와 조용히
 * 어긋날 수 있는 두 번째 플래그를 추가하는 게 아니다.
 *
 * 여기 있는 모든 멤버는 `ReaderScreen`의 같은 모달 바텀 시트를 통해 열리며, 예외가 하나 있다.
 * [TableOfContents]는 그 분기 이전에 걸러져서 대신 사이드 내비게이션 드로어를 구동하는데, 리더가 이리저리
 * 이동하는 데 쓰는 아웃라인은 설정 폼이 아니라 내비게이션 chrome처럼 동작하며 시트가 아닌 드로어 자체의
 * 열고 닫는 방식이 필요하기 때문이다.
 */
sealed interface ReaderOptionSheet {
    /**
     * 문서 아웃라인. 이 계층의 다른 모든 멤버와 달리 공유 모달 바텀 시트를 통해 렌더링되지 않는다 —
     * 표제로 점프하는 것은 조정할 설정이 아니라 내비게이션 동작이므로 `ReaderScreen`이 이를 사이드
     * 내비게이션 드로어로 특별 취급한다.
     */
    data object TableOfContents : ReaderOptionSheet

    /** 페이지 번호 입력란과 이동 버튼으로, 문서가 아는 페이지 수 범위 안으로 제한된다. */
    data object GoToPage : ReaderOptionSheet

    /** 화면 전반의 보기 동작: 화면 항상 켜기, 전체 화면, 진행률 표시줄 토글, 그리고 visual/PDF 문서라면 확대 배율. */
    data object View : ReaderOptionSheet

    /** 텍스트 기반 형식의 텍스트 렌더링: 활자 크기, 줄 간격, 활자 패밀리를, 조합된 결과의 실시간 미리보기와 함께. */
    data object Font : ReaderOptionSheet

    /** 리더의 색상 구성(시스템/라이트/다크/세피아)으로, 적용 전에 현재 페이지 style에 대비해 미리 볼 수 있다. */
    data object Theme : ReaderOptionSheet

    /** 페이지 넘김이 어떻게 트리거되고 애니메이션되는지: 넘김 축, 기본 전환, 페이지 넘김 효과. */
    data object PageTurn : ReaderOptionSheet

    /** 자동 스크롤 활성화 여부와 그 모드(픽셀/줄/페이지), 속도 — visual 문서가 열려 있는 동안에는 줄 단위로 스크롤할 텍스트가 없으므로 모드 선택기 자체가 줄 모드를 비활성화한다. */
    data object AutoScroll : ReaderOptionSheet

    /** 디스플레이 자체의 최소 밝기 한계보다 더 어둡게 읽기 위해 화면 전체에 그려지는 어둡게 하기 오버레이. */
    data object Brightness : ReaderOptionSheet

    /**
     * 하단 바의 페이지 진행률 표시를 위한, 좁게 범위가 잡힌 스위치 하나 — [View]도 더 넓은 화면 동작 옵션과
     * 함께 같은 토글을 노출하지만, 그 설정 하나에만 도달하고 싶은 독자를 위해 설명을 곁들여 여기서 다시
     * 단독으로 제공한다.
     */
    data object Controls : ReaderOptionSheet
}
