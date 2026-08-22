package com.tedd.teddreader.core.data.parser

import okio.FileSystem

/**
 * The real filesystem this platform's parsers read and write scratch files through — okio's own
 * `FileSystem.SYSTEM`, which both platforms happen to provide identically today. This exists as a
 * function, called through a default parameter (e.g. `fileSystem: FileSystem = systemFileSystem()`),
 * rather than callers referencing `FileSystem.SYSTEM` directly, so a test can substitute a fake
 * [FileSystem] for that parameter instead of touching real disk.
 *
 * @return This platform's [FileSystem.SYSTEM].
 */
internal expect fun systemFileSystem(): FileSystem
