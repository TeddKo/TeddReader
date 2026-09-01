package com.tedd.teddreader.core.data.parser

import okio.FileSystem

/**
 * 이 플랫폼의 파서들이 스크래치 파일을 읽고 쓰는 실제 파일시스템 — okio 자체의 `FileSystem.SYSTEM`이며,
 * 오늘날 두 플랫폼 모두 우연히 동일하게 제공한다. 이것이 함수로 존재하고 기본 매개변수를 통해
 * 호출되는 이유(예: `fileSystem: FileSystem = systemFileSystem()`)는, 호출자가 `FileSystem.SYSTEM`을
 * 직접 참조하는 대신 테스트가 실제 디스크를 건드리지 않고 그 매개변수에 가짜 [FileSystem]을
 * 대체할 수 있게 하기 위해서다.
 *
 * @return 이 플랫폼의 [FileSystem.SYSTEM].
 */
internal expect fun systemFileSystem(): FileSystem
