package com.tedd.teddreader.core.data.pagination

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Android text layout (StaticLayout under Compose's TextMeasurer) is safe off the main thread, so
 * pagination keeps measuring in the background the way it always has — a whole-book repagination on the
 * main thread would starve the UI (the reason measurement was moved off it in the first place).
 */
internal actual val ReaderPageMeasureDispatcher: CoroutineDispatcher = Dispatchers.Default
