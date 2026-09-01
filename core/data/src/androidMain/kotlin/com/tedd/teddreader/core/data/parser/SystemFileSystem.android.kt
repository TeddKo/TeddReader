package com.tedd.teddreader.core.data.parser

import okio.FileSystem

/** [systemFileSystem] 계약의 안드로이드 구현: okio의 실제 시스템 파일시스템. */
internal actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
