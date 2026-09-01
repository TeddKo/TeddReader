package com.tedd.teddreader.core.datastore

/**
 * 두 플랫폼이 공유하는 환경설정 파일 이름이다.
 *
 * 공통 코드에서 Android와 iOS가 같은 파일을 쓰고 읽어야 하므로 플랫폼 환경설정 API 대신
 * JSON을 사용한다. 어느 플랫폼 빌더도 별도 파일을 사용해 독자의 설정을 조용히 잃지 않도록
 * 이름을 이곳에 둔다.
 */
const val ReaderPreferencesFileName = "reader_preferences.json"
