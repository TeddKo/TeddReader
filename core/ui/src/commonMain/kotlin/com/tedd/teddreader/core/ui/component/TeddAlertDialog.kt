package com.tedd.teddreader.core.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderShapes
import com.tedd.teddreader.core.designsystem.teddReaderTypography

/**
 * 사용자에게 확인 또는 취소를 요구하는 모달 다이얼로그로, 앱 토큰에 맞춰 스타일링되어 있다.
 *
 * Material의 다이얼로그를 새로 만드는 대신 감싼다. 다이얼로그는 다른 모든 것 위의 플랫폼 창에
 * 스스로를 배치하고, 열려 있는 동안 포커스를 가두고, 닫힐 때 포커스를 복원하고, 뒤로 가기 제스처를
 * 라우팅해야 한다 — 접근성 서비스나 하드웨어 키보드가 실제로 사용하기 전까지는 보이지 않는 채로,
 * 미묘하게 잘못되기 쉬운 동작이다. Material이 이미 이 모든 것을 올바르게 처리하고 있으므로, 여기서의
 * 가치는 Material의 기본값 대신 서피스, 모양, 활자를 이 앱의 스케일에 고정하고 Material import를 이
 * 모듈 안에만 두는 데 있다.
 *
 * 이 다이얼로그는 다른 무엇이 이어지기 전에 사용자가 반드시 내려야 하는 결정 — 파괴적 확인, 필수
 * 선택 — 을 위해서만 사용한다. 사용자가 둘러보거나, 닫거나, 무시할 수 있는 것은 같은 방식으로
 * 포커스를 빼앗지 않는 [TeddModalBottomSheet]에 속한다.
 *
 * @param onDismissRequest 사용자가 결정을 내리지 않고 닫을 때 — 바깥을 탭하거나 뒤로 가기
 * 제스처를 사용할 때 — 호출된다. 하위 상태를 그대로 두어야 한다. 이 방식으로 닫힌 다이얼로그는
 * 확인이 아니라 취소다.
 * @param confirmButton 다이얼로그가 물었던 작업을 실제로 수행하는 액션. 파괴적 작업이라면 안전한
 * 기본값처럼 읽히지 않도록 파괴적 강조를 부여한다.
 * @param modifier 다이얼로그 서피스에 적용되는 modifier.
 * @param dismissButton 명시적인 취소 액션이며, null이면 생략된다. 바깥을 탭하는 것은 눈에 띄게
 * 거절하는 방법이 아니므로, 모든 파괴적 다이얼로그에는 이 값을 제공해야 한다.
 * @param title 곧 일어날 일을 밝히는 다이얼로그 제목이며, null이면 생략된다.
 * @param text 사용자가 동의하는 결과를 담는 다이얼로그 본문이며, null이면 생략된다.
 */
@Composable
fun TeddAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: String? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val colors = teddReaderColors()
    val typography = teddReaderTypography()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title?.let { { TeddText(text = it, style = typography.titleLarge) } },
        text = text,
        shape = teddReaderShapes().large,
        containerColor = colors.surfaceContainerLow,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurfaceVariant,
    )
}

/** [TeddAlertDialog]를 파괴적 확인으로 렌더링하는 Compose 프리뷰. */
@Preview
@Composable
private fun TeddAlertDialogPreview() {
    TeddAlertDialog(
        onDismissRequest = {},
        title = "Delete document?",
        text = { TeddText(text = "The file on your device is not affected.") },
        confirmButton = {
            TeddButton(
                text = "Delete",
                onClick = {},
                emphasis = TeddButtonEmphasis.Destructive,
            )
        },
        dismissButton = { TeddButton(text = "Cancel", onClick = {}, emphasis = TeddButtonEmphasis.Text) },
    )
}
