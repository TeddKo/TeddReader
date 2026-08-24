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
 * The reader settings screen: hosts [ReaderSettingsSheet] inside a scaffold with its own top bar,
 * capping the sheet's width via [TeddReaderBreakpoints.readableMaxWidth][com.tedd.teddreader.core.designsystem.TeddReaderBreakpoints.readableMaxWidth]
 * so its rows do not stretch to unreadable line lengths on a tablet- or desktop-sized window. Past
 * that width the settings rows' labels and controls would spread out with far more blank space
 * between them than a control needs, rather than reading as a normal-width settings list.
 *
 * @param onBack invoked when the user leaves this screen via the top bar's back action.
 * @param modifier applied to the outer [TeddScaffold].
 * @param viewModel supplies [ReaderSettingsUiState] and the update callbacks wired into
 *   [ReaderSettingsSheet]; defaults to one resolved through Koin.
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
