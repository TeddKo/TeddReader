package com.tedd.teddreader.feature.reader.impl

sealed interface ReaderOptionSheet {
    data object TableOfContents : ReaderOptionSheet
    data object GoToPage : ReaderOptionSheet
    data object View : ReaderOptionSheet
    data object Font : ReaderOptionSheet
    data object Theme : ReaderOptionSheet
    data object PageTurn : ReaderOptionSheet
    data object AutoScroll : ReaderOptionSheet
    data object Brightness : ReaderOptionSheet
    data object Controls : ReaderOptionSheet
}
