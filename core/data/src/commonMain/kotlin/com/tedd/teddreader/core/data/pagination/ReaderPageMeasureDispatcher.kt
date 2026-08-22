package com.tedd.teddreader.core.data.pagination

import kotlinx.coroutines.CoroutineDispatcher

/**
 * The dispatcher a real page-breaking measurement must run on.
 *
 * A [com.tedd.teddreader.core.common.model.ReaderPageBreaker] lays text out with the UI framework's own
 * text stack, and whether that stack tolerates a second thread is a *platform* fact, not a repository
 * decision:
 *
 * - On Android, platform text layout is safe to run off the main thread, and pagination deliberately
 *   measures on a background dispatcher so a whole-book repagination never starves the UI.
 * - On iOS, Compose's Skia text stack shares unsynchronized process-global state across every
 *   measurer — `ParagraphBuilder.skiko.kt`'s file-level `skTextStylesCache` is a plain `HashMap` whose
 *   `getOrPut` prunes with `entries.removeAll`. Measuring on a background thread while the main thread
 *   lays out the visible page raced those two through the same map and crashed with
 *   `ConcurrentModificationException`. Until that stack is thread-safe upstream, every measurement must
 *   share the main thread with drawing, which serialises them by construction.
 */
internal expect val ReaderPageMeasureDispatcher: CoroutineDispatcher
