package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * The system bars' insets the reader's own layout must reserve room for — used to size and pad the
 * reader's panes and its top/bottom chrome so neither ever draws under the status bar or the
 * navigation bar. Each platform answers with whatever its own `WindowInsets` API considers "the
 * system bars," on whatever terms that platform's own actual documents.
 *
 * @return the system bars' insets, as this platform currently reports them.
 */
@Composable
internal expect fun readerSystemBarsInsets(): WindowInsets
