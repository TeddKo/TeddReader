package com.tedd.teddreader.core.ui.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tedd.teddreader.core.designsystem.teddReaderColors

/**
 * The app's screen frame: a Material `Scaffold` with the app's own background already applied.
 *
 * It exists so no screen has to remember to set `containerColor` — a screen that forgets renders on
 * Material's default surface, which is close enough to the app's background to pass review and wrong enough
 * to see beside another screen.
 *
 * @param modifier applied to the scaffold.
 * @param topBar the screen's top bar, empty by default.
 * @param bottomBar the screen's bottom bar, empty by default.
 * @param content the screen body; receives the insets the bars leave behind and must apply them.
 */
@Composable
fun TeddScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = ScaffoldDefaults.contentWindowInsets,
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = teddReaderColors()
    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.onBackground,
        topBar = topBar,
        bottomBar = bottomBar,
        contentWindowInsets = contentWindowInsets,
        content = content,
    )
}
