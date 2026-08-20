package com.tedd.teddreader.feature.settings.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tedd.teddreader.core.designsystem.DefaultTeddReaderSpacing
import com.tedd.teddreader.core.ui.component.TeddIconButton
import com.tedd.teddreader.core.ui.component.TeddScaffold
import com.tedd.teddreader.core.ui.component.TeddTopBar
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.back
import com.tedd.teddreader.core.ui.generated.resources.settings
import com.tedd.teddreader.core.ui.icon.TeddIcons
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private val ScreenMaxWidth = 720.dp

@Composable
fun ReaderSettingsRouteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReaderSettingsViewModel = koinViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    TeddScaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TeddTopBar(
                title = stringResource(Res.string.settings),
                navigationIcon = {
                    TeddIconButton(onClick = onBack, contentDescription = stringResource(Res.string.back)) {
                        Icon(imageVector = TeddIcons.Back, contentDescription = null)
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
                    .widthIn(max = ScreenMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = DefaultTeddReaderSpacing.screenPadding),
            )
        }
    }
}
