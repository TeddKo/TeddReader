package com.tedd.teddreader.core.data.parser

import okio.FileSystem

/** [systemFileSystem] 계약에 대한 iOS 구현: okio의 실제 시스템 파일시스템. */
internal actual fun systemFileSystem(): FileSystem = FileSystem.SYSTEM
