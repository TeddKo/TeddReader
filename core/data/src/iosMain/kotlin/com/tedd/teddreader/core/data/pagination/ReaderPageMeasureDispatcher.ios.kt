package com.tedd.teddreader.core.data.pagination

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Compose's Skia text stack is not thread-safe (see the expect declaration for the exact shared map),
 * so on iOS every real measurement runs on the main dispatcher, where it is serialised with the page
 * surface's own layout by sharing its thread. Plain [Dispatchers.Main] rather than `.immediate`: a
 * measurement batch should queue behind pending frames, not preempt them.
 */
internal actual val ReaderPageMeasureDispatcher: CoroutineDispatcher = Dispatchers.Main
