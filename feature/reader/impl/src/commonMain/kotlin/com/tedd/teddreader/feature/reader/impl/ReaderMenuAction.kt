package com.tedd.teddreader.feature.reader.impl

/**
 * 리더 자체 액션 메뉴가 제공할 수 있는 동작 하나로, 직접 명령으로 처리되거나 `ReaderOptionSheet`의 옵션
 * 시트 중 하나를 열어 처리된다. 대부분의 이름은 무엇을 여는지로 자명하게 설명된다. 짚어둘 만한 한 쌍은
 * [ToggleSavedPlace]와 [SavedPlaces]인데, 전자는 *현재* 페이지를 그 자리에서 책갈피로 저장하거나 제거하고,
 * 후자는 이 문서에 저장된 위치의 전체 목록을 연다.
 */
enum class ReaderMenuAction {
    Search,
    ToggleSavedPlace,
    SavedPlaces,
    TableOfContents,
    GoToPage,
    ViewOptions,
    FontOptions,
    ThemeOptions,
    PageTurnOptions,
    AutoScrollOptions,
    BrightnessOptions,
    ControlOptions,
    DocumentInfo,
}
