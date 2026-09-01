package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.TeddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * [TeddSection]이 어떤 종류의 블록인지, 그리고 그에 따라 위에 얼마만큼의 여백이 놓이는지를 정한다.
 *
 * 의도적으로 네 가지로 닫힌 집합이다. 이것이 존재하기 전에는 한 화면이 같은 개념을 헤더 블록을 위한
 * 익명 `Column`, 컬렉션을 위한 이름 있는 컴포저블, 다른 화면의 인라인 코드, 시트 안의 옵션 그룹이라는
 * 네 가지 다른 방식으로 표현했고, 그 각각이 자기만의 여백을 선택했다. 네 가지 종류에 이름을 붙이는
 * 것이 바로 "이것이 어떤 블록인가?"를 답이 있는 질문으로 만든다.
 *
 * 중요한 구분은 [Collection]과 나머지의 구분이다: 컬렉션은 제목이 다른 섹션들과 정렬을 유지하는 동안
 * 본문을 화면 가장자리까지 뻗을 수 있고, 다른 어떤 것도 그럴 수 없다.
 */
enum class TeddSectionKind {
    /**
     * 화면의 정체성과 그 최상위 액션. 화면당 정확히 하나이며 스크롤에서 가장 먼저 온다. 그 앞에
     * 아무것도 없으므로 위에 여백을 갖지 않는다.
     */
    Masthead,

    /**
     * 배너, 오류, 빈 상태, 또는 로딩 인디케이터 — 조건부이며 같은 종류의 다른 것들과 상호 배타적이다.
     * 자기 자신의 섹션이라기보다 주변 콘텐츠의 한 상태이므로, 실제 섹션보다 이웃에 더 가깝게 붙는다.
     */
    Status,

    /**
     * 문서, 폴더, 또는 결과의 모음으로, 본문 위에 선택적 제목이 붙을 수 있다. 본문이 화면의 가로
     * 인셋을 무시할 수 있는 유일한 종류로, 가로로 스크롤되는 카드 행이 가장자리까지 뻗을 수 있게
     * 한다.
     */
    Collection,

    /**
     * 필터, 정렬 옵션, 설정 같은 묶인 컨트롤들. 콘텐츠라기보다 선택지의 집합으로 읽히므로, 본문은
     * 항상 화면 인셋을 유지한다.
     */
    Form,
}

/**
 * 이 종류의 섹션 위에 놓이는 여백.
 *
 * @receiver 간격이 매겨지는 섹션의 종류.
 * @param spacing 테마의 간격 스케일.
 * @return 섹션 위에 놓을 여백: 화면의 첫 블록이면 없음, 일시적인 상태면 더 촘촘한 아이템 간격, 자기
 * 자신의 섹션으로 읽히는 그 밖의 모든 것이면 완전한 섹션 간격.
 */
private fun TeddSectionKind.topGap(spacing: TeddReaderSpacing) = when (this) {
    TeddSectionKind.Masthead -> spacing.none
    TeddSectionKind.Status -> spacing.itemGap
    TeddSectionKind.Collection, TeddSectionKind.Form -> spacing.sectionGap
}

/**
 * 화면의 한 블록으로, 예전에는 각 호출부에서 따로따로 결정하던 세 가지 — 위쪽 여백, 주위의 가로
 * 인셋, 제목이 그려지는 방식 — 를 소유한다.
 *
 * 화면은 콘텐츠와 [kind]를 넘길 뿐, 섹션 위의 여백을 직접 고르지 않는다. 이 역전이 핵심이다. 각
 * 블록이 자기만의 여백을 고를 때는, 홈 화면의 모든 구조적 여백이 결국 똑같은 24dp 값으로 귀결되어
 * 섹션 경계와 한 섹션 안 두 아이템 사이의 간격이 시각적으로 동일해져 버렸다 — 화면은 하나의 평평한
 * 목록처럼 읽혔고 그 위계는 보이지 않았다. 모든 블록을 이곳을 거치게 하면 [TeddSectionKind]가
 * 결정하고, 세 가지 여백 토큰은 구별 가능한 채로 남는다. 본문 column 안의 배치는 [verticalArrangement]
 * 를 통해 호출자가 제공한다.
 *
 * 가로 인셋이 스크롤 컨테이너가 아니라 여기에 있는 것이 이 수정의 나머지 절반이다. 가로
 * `contentPadding`을 적용하는 `LazyColumn`은 화면 가장자리까지 뻗어야 하는 가로 스크롤 카드 행을
 * 포함해 모든 자식을 그 인셋 안에 가두어 버린다. 인셋을 섹션마다 소유하게 하면, [fullBleed]는 제목이
 * 다른 모든 섹션과 정렬을 유지하는 동안 컬렉션의 본문이 가장자리에 닿게 해 준다. 스크롤 컨테이너는
 * 세로 인셋만 소유하게 남는다.
 *
 * @param kind 이 블록이 어떤 종류인지. 위쪽 여백을 결정한다. [TeddSectionKind] 참고.
 * @param modifier 섹션 자체의 여백과 인셋 바깥, 섹션 루트에 적용되는 modifier.
 * @param title 섹션의 제목. null이면 생략되며, 이는 자체 헤더 콘텐츠를 그리는
 * [TeddSectionKind.Status]와 [TeddSectionKind.Masthead] 블록에서는 정상이다.
 * @param description [title] 아래, 개수나 한 줄짜리 설명을 위한 더 조용한 두 번째 줄. [title]이
 * null이면 무시된다. 수식할 대상이 없기 때문이다.
 * @param action 제목 행의 후행 컨트롤 — "모두 보기" 등. [title]이 null이면 무시된다. 제목이 없으면
 * 그것을 놓을 행도 없기 때문이다.
 * @param fullBleed [content]가 가로 화면 인셋을 무시할지 여부. 본문이 가로로 스크롤되며 자체 선행
 * 인셋을 제공하는 [TeddSectionKind.Collection]에서만 true다. 제목은 어느 쪽이든 화면 인셋을
 * 유지하므로, 제목들은 화면을 따라 계속 정렬된다.
 * @param verticalArrangement 섹션 본문 column 안 아이템들에 적용되는 배치. null이면 테마의 아이템
 * 간격 토큰을 간격으로 사용한다 — 문서 행이나 설정 항목 목록에 맞는 기본값이다. 본문이 그리드나 가로
 * 페이저처럼 아이템 간 간격이 의미가 없는 단일 자식을 담고 있을 때는 [Arrangement.Top]을 전달한다.
 * 의도적으로 더 촘촘한 배치를 원할 때는 다른 `spacedBy` 토큰을 전달한다. 이 섹션 위의 여백은 여기서
 * 제어되지 않는다 — 화면 전체에서 위계를 눈에 보이게 유지하기 위해 [TeddSectionKind]가 소유한다.
 * @param content 섹션의 본문.
 */
@Composable
fun TeddSection(
    kind: TeddSectionKind,
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
    fullBleed: Boolean = false,
    verticalArrangement: Arrangement.Vertical? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = teddReaderSpacing()
    val typography = teddReaderTypography()
    val colors = teddReaderColors()
    val resolvedArrangement = verticalArrangement ?: Arrangement.spacedBy(spacing.itemGap)

    Column(modifier = modifier.fillMaxWidth().padding(top = kind.topGap(spacing))) {
        if (title != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    TeddText(text = title, style = typography.titleMedium)
                    if (description != null) {
                        TeddText(
                            text = description,
                            style = typography.settingDescription,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                if (action != null) {
                    action()
                }
            }
        }

        Column(
            modifier = if (fullBleed) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.fillMaxWidth().padding(horizontal = spacing.screenPadding)
            }.padding(top = if (title != null) spacing.sectionHeaderGap else spacing.none),
            verticalArrangement = resolvedArrangement,
            content = content,
        )
    }
}

/** 제목, 설명, 후행 액션을 갖춘 컬렉션 섹션을 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddSectionPreview() {
    TeddPreviewSurface {
        TeddSection(
            kind = TeddSectionKind.Collection,
            title = "Recent reading",
            description = "Twelve documents",
            action = { TeddButton(text = "View all", onClick = {}, emphasis = TeddButtonEmphasis.Text) },
        ) {
            TeddText(text = "A document row")
            TeddText(text = "Another document row")
        }
    }
}

/**
 * 단일 자식 섹션 — 본문을 채우는 그리드나 페이저 — 의 프리뷰로, 기본 아이템 간격 배치가
 * [Arrangement.Top]으로 억제되어 있다.
 */
@Preview
@Composable
private fun TeddSectionSingleChildPreview() {
    TeddPreviewSurface {
        TeddSection(
            kind = TeddSectionKind.Collection,
            title = "Pinned",
            verticalArrangement = Arrangement.Top,
        ) {
            TeddText(text = "A full-width grid or pager lives here")
        }
    }
}
