package com.tedd.teddreader.core.room.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 진행 중인 세션을 갱신하고 끝날 때 닫을 수 있도록 자체 id를 키로 사용하는 하나의 독서 세션입니다.
 *
 * 통계는 [activeMillis]를 합산합니다. 두 끝점 사이의 실제 경과 시간에는 화면이 잠긴 시간도 포함되며,
 * [endedAtEpochMillis]는 세션이 열려 있는 동안 null이므로 강제 종료와 정상 종료를 구분합니다. 현재 이 행을
 * 기록하는 코드는 없습니다(ReadingStatsRepository 참고).
 *
 * @property id 열린 세션을 갱신한 뒤 닫을 수 있게 하는 세션 자체의 키입니다.
 * @property documentId 읽고 있는 책입니다.
 * @property startedAtEpochMillis 세션을 시작한 시각입니다.
 * @property endedAtEpochMillis 세션을 끝낸 시각이며, 아직 열려 있으면 NULL입니다.
 * @property activeMillis 실제로 읽은 시간이며 통계가 합산하는 값입니다.
 * @property startLocation 리더 자체의 축약형 위치 문자열로 표현한 세션 시작 위치입니다.
 * @property endLocation 세션 종료 위치이며, 아직 열려 있으면 NULL입니다.
 */
@Entity(
    tableName = "reading_sessions",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("documentId")],
)
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long? = null,
    val activeMillis: Long,
    val startLocation: String,
    val endLocation: String? = null,
)
