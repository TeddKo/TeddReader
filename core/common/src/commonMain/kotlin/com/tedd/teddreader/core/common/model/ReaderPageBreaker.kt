package com.tedd.teddreader.core.common.model

/**
 * 리더 자체의 텍스트 레이아웃이 페이지 경계를 두는 위치를 보고한다.
 *
 * 꼬리를 숨기지 않고 페이지를 채우려면 페이지 나누기와 렌더러가 일치해야 하며, 어느 쪽도 추정할 수 없다. 문자 너비 추정은 단어 줄바꿈이나 글리프별 진행 폭을 알 수 없고, `viewportHeight / lineHeight` 줄 수 계산은 리더의 줄 높이가 글꼴의 자연 높이보다 낮아질 때 줄 상자가 커지는 현상이나 대체 글꼴이 담당한 한 줄만 더 높아지는 현상을 알 수 없다. UI 계층이 실제 레이아웃을 소유하고 둘 다 측정한다.
 *
 * @see pageStarts
 */
fun interface ReaderPageBreaker {
    /**
     * 텍스트 한 구간의 페이지 시작 위치를 측정한다.
     *
     * @param text 리더가 그릴 한 섹션 분량의 레이아웃 대상 텍스트.
     * @param blocks 해당 텍스트의 블록 구조. 이미지를 대신하는 문자 하나가 아니라 실제 그릴 상자만큼 공간을 차지하게 한다.
     * @return 각 페이지 첫 문자의 오프셋을 0부터 오름차순으로 담은 배열. 빈 텍스트면 비어 있다.
     */
    fun pageStarts(text: String, blocks: List<ReaderBlock>): IntArray
}
