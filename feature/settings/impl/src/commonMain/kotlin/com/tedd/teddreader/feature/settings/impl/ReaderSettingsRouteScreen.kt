package com.tedd.teddreader.feature.settings.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.designsystem.teddReaderBreakpoints
import com.tedd.teddreader.core.designsystem.teddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddIcon
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.back
import com.tedd.teddreader.core.ui.generated.resources.settings
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * 자체 상단 바가 있는 스캐폴드 안에 [ReaderSettingsSheet]를 배치하는 리더 설정 화면이다.
 * 태블릿이나 데스크톱 크기의 창에서 행이 읽기 어려울 만큼 길어지지 않도록
 * [TeddReaderBreakpoints.readableMaxWidth][com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints.readableMaxWidth]로
 * 시트 너비를 제한한다. 이 너비를 넘으면 설정 행의 라벨과 컨트롤 사이에 필요한 것보다
 * 훨씬 많은 빈 공간이 생겨 일반적인 너비의 설정 목록처럼 읽히지 않는다.
 *
 * @param onBack 사용자가 상단 바의 뒤로 가기 동작으로 이 화면을 떠날 때 호출할 콜백.
 * @param modifier 바깥쪽 [TeddScaffold]에 적용할 값.
 * @param viewModel [ReaderSettingsUiState]와 [ReaderSettingsSheet]에 연결할 업데이트 콜백을
 *   제공하며, 기본값은 Koin으로 해석한 인스턴스다.
 */
@Composable
fun ReaderSettingsRouteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val spacing = teddReaderSpacing()
    val breakpoints = teddReaderBreakpoints()

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.settings),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        TeddIcon(imageVector = TeddIcons.Back, contentDescription = null)
                    }
                },
            )
        },
    ) { scaffoldPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            ReaderSettingsSheet(
                uiState = uiState,
                onStyleChange = viewModel::updateStyle,
                onPageTurnModeChange = viewModel::updatePageTurnMode,
                onPageAnimationChange = viewModel::updatePageAnimation,
                onAutoScrollConfigChange = viewModel::updateAutoScrollConfig,
                onAppLanguageChange = viewModel::updateAppLanguage,
                modifier = Modifier
                    .widthIn(max = breakpoints.readableMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = spacing.screenPadding),
            )
        }
    }
}
