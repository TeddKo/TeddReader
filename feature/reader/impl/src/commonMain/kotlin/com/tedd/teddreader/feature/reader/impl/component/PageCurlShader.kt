package com.tedd.teddreader.feature.reader.impl.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tedd.teddreader.core.common.model.PageTurnMode

internal expect fun platformPageCurlShaderSupported(): Boolean

@Composable
internal expect fun PlatformPageCurlShaderOverlay(
    dragOffset: Float,
    crossOffset: Float,
    progress: Float,
    pageTurnMode: PageTurnMode,
    preset: CurlPreset,
    modifier: Modifier = Modifier,
)
