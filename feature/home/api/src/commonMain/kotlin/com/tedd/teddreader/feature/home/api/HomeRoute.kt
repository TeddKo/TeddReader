package com.tedd.teddreader.feature.home.api

import kotlinx.serialization.Serializable

/** 앱 실행 후 표시되는 진입점인 홈 화면으로 이동한다. */
@Serializable
data object HomeRoute

/**
 * 문서 라이브러리로 이동한다.
 *
 * @property folderId 표시할 폴더. null이면 특정 폴더 대신 전체 라이브러리를 표시한다.
 */
@Serializable
data class LibraryRoute(
    val folderId: String? = null,
)
