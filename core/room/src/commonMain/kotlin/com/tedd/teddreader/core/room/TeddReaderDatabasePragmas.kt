package com.tedd.teddreader.core.room

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 선행 기록 로그가 유지할 수 있는 최대 크기를 제한합니다.
 *
 * 책을 가져올 때 모든 섹션을 하나의 트랜잭션으로 쓰므로 로그는 전체 내용을 담을 만큼 커집니다. 528개 챕터인
 * 책에서는 19.9 MB였습니다. 커밋하면 해당 페이지가 데이터베이스로 돌아가지만 파일은 최대 크기를 유지하므로
 * 공간이 반환되지 않습니다. 대신 SQLite가 매 체크포인트마다 로그를 이 제한까지 줄이며, 앱에서 별도로 작업을
 * 예약할 필요가 없습니다.
 *
 * 저널 모드 자체는 WAL로 유지합니다. 롤백 저널로 바꾸어도 파일 크기를 제한할 수 있지만, 모든 쓰기에서
 * fsync를 두 번 수행하고 읽기와 쓰기가 단일 연결을 공유하게 됩니다. 이 리더는 페이지를 넘길 때마다 진행 위치
 * 행을 씁니다.
 *
 * @receiver 플랫폼 빌더 내부에서 체이닝할 수 있도록 설정 중인 데이터베이스 빌더입니다.
 * @return 모든 연결에 제한을 적용하는 열기 콜백이 추가된 동일한 빌더입니다.
 */
internal fun RoomDatabase.Builder<TeddReaderDatabase>.withWalSizeLimit(): RoomDatabase.Builder<TeddReaderDatabase> =
    addCallback(
        object : RoomDatabase.Callback() {
            override suspend fun onOpen(connection: SQLiteConnection) {
                connection.execSQL("PRAGMA journal_size_limit = $WalSizeLimitBytes")
            }
        },
    )

/** 4 MB: 일반 트랜잭션이 넘치지 않을 만큼 크고, 공간을 반환할 만큼 작습니다. */
private const val WalSizeLimitBytes = 4 * 1024 * 1024
