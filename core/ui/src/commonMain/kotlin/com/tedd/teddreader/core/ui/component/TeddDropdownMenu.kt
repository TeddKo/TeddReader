package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * [TeddDropdownMenuItem] 행들을 담으며, 그것이 컴포즈된 위치 어디든 앵커링되는 오버플로우 메뉴.
 *
 * [TeddAlertDialog]와 같은 이유로 Material의 메뉴를 새로 만드는 대신 감싼다: 메뉴는 플랫폼 팝업에서
 * 열리고, 앵커 근처 화면 안에 머물도록 스스로 위치를 잡고, 포커스를 가두었다가 복원하며, 뒤로 가기나
 * 바깥 탭에 닫힌다. 이를 올바르게 재구현해도 얻는 것이 없다. 이 래퍼가 더하는 것은 Material 대신
 * 앱의 서피스와 모양이며, Material import를 이 모듈 안에 가두어 오버플로우 메뉴를 보여주는 화면이
 * `material3` import를 필요로 하지 않게 한다.
 *
 * 메뉴는 부모를 기준으로 스스로 위치를 잡으므로, 이를 여는 컨트롤과 같은 컨테이너 안에 두어야
 * 한다 — 보통은 아이콘 버튼도 함께 담고 있는 `Box`다.
 *
 * @param expanded 메뉴가 표시 중인지 여부. 메뉴를 여는 컨트롤이 메뉴 자체의 컴포지션 바깥에 있으므로
 * 이 상태는 호출자가 소유한다.
 * @param onDismissRequest 사용자가 선택 없이 메뉴를 닫을 때 — 바깥 탭이나 뒤로 가기 제스처 —
 * 호출된다. [expanded]가 읽는 상태를 반드시 초기화해야 하며, 그렇지 않으면 메뉴를 다시 열 수 없다.
 * @param modifier 메뉴 서피스에 적용되는 modifier.
 * @param content 메뉴의 행들로, 보통은 [TeddDropdownMenuItem] 호출들이다.
 */
@Composable
fun TeddDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = teddReaderColors().surfaceContainerLow,
        shape = teddReaderShapes().small,
        content = content,
    )
}

/**
 * [TeddDropdownMenu] 안의 한 행. [DropdownMenuItem]을 감싸지만 그 라벨을 [teddReaderTypography]의
 * `settingTitle` 스타일로 고정하는 것 외에는 Material 자체의 [DropdownMenuItem] 파라미터를 그대로
 * 전달할 뿐이다.
 *
 * @param text 항목의 라벨.
 * @param onClick 항목이 탭될 때 호출된다. 감싸고 있는 [TeddDropdownMenu] 자체를 닫는 것은 호출자의
 * 책임이다.
 * @param modifier 내부 [DropdownMenuItem]에 적용되는 modifier.
 * @param enabled 항목이 탭에 반응할지 여부.
 * @param leadingIcon 라벨 앞에 표시되는 콘텐츠. null이면 생략된다.
 * @param trailingIcon 라벨 뒤에 표시되는 콘텐츠. null이면 생략된다.
 */
@Composable
fun TeddDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val typography = teddReaderTypography()
    DropdownMenuItem(
        text = { TeddText(text = text, style = typography.settingTitle) },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
    )
}

/** 두 개의 [TeddDropdownMenuItem]과 함께 펼쳐진 [TeddDropdownMenu]를 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddDropdownMenuPreview() {
    TeddPreviewSurface {
        Box(modifier = Modifier.padding(teddReaderSpacing().medium)) {
            TeddDropdownMenu(
                expanded = true,
                onDismissRequest = {},
            ) {
                TeddDropdownMenuItem(text = "Search", onClick = {})
                TeddDropdownMenuItem(text = "Document info", onClick = {})
            }
        }
    }
}
