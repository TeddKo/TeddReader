package com.tedd.teddreader.core.data.parser

import okio.FileSystem

/** iOS's implementation of the [systemFileSystem] contract: okio's real system filesystem. */
internal actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
