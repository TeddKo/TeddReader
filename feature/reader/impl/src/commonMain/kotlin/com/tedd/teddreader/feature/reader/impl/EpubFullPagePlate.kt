package com.tedd.teddreader.feature.reader.impl

import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.isBlankIgnoringObjects

/**
 * 페이지 전체를 온전히 내어준 그림, 또는 페이지에 읽을 텍스트가 있으면 null.
 *
 * 텍스트를 전혀 공유하지 않는 판(plate)이라도 여전히 텍스트 흐름 안에 배치되어 있어서, 그림이 페이지 상단에
 * 고정되고 나머지는 공백으로 남는다. 표지는 그런 식으로 다뤄지지 않으며 이상하게 보인 적이 없으므로, 그림 한
 * 장만 담은 페이지는 표지와 같은 방식으로 — 페이지를 가득 채우고 중앙에 놓아 — 그린다.
 *
 * 단 한 단어라도 텍스트를 담고 있는 페이지는 그대로 둔다. 그 그림은 흐름 안에 속해 있으므로, 옮기면 그것이
 * 쓰인 단락에서 뜯어내는 셈이 된다.
 *
 * 이미지 블록이 정확히 하나뿐인(표지가 아닌) 페이지만 판으로 인정한다. 그림 두 장이 한 페이지를 나눠 쓰는
 * 경우 여전히 텍스트 레이아웃이 순서대로 쌓아야 하는데, 그 순서를 아는 것은 텍스트 레이아웃뿐이므로, 표지가
 * 아닌 경우에는 [blocks]를 `firstOrNull`이 아닌 `singleOrNull`로 탐색한다.
 *
 * @param text 페이지의 순수 텍스트로, 페이지가 그 외에는 비어 있는지 확인하는 용도로만 쓰인다.
 * @param blocks 페이지의 구조화된 콘텐츠. 표지 이미지를 먼저 찾고, 없으면 단독 이미지 하나를 찾는다.
 * @return [blocks]에 표지 이미지가 있으면 그 블록, 없고 [text]가 비어 있으며 단독 이미지 블록이 정확히
 *   하나면 그 블록; 페이지에 실제 텍스트가 있거나 이미지가 두 장 이상이면 null.
 */
internal fun epubFullPagePlate(text: String, blocks: List<ReaderBlock>): ReaderBlock? {
    blocks.firstOrNull { it.kind == ReaderBlockKind.COVER_IMAGE }?.let { return it }
    if (!text.isBlankIgnoringObjects()) return null
    return blocks.singleOrNull { it.kind == ReaderBlockKind.IMAGE && it.imageHref != null }
}
