package com.tedd.teddreader.core.data.parser

import okio.FileSystem

internal actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
