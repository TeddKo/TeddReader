package com.tedd.teddreader.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Material의 아이콘 라이브러리 대신 리더 크롬을 위해 직접 그린 [ImageVector]들. 이 앱은 실제로
 * 필요한 소수의 글리프를 위한 큰 아티팩트인 `material-icons-extended`에 의존하지 않으므로, 여기의
 * 각 아이콘은 리더의 컨트롤(내비게이션, 북마크, 재생, 배터리, 삭제)만을 정확히 다루는 최소한의 경로
 * 정의다. 모든 경로는 테마와 무관하게 `Color.Black`으로 채우거나 획을 긋는다; 그 값이 실제 렌더링
 * 색상은 아니다 — Material의 `Icon` 컴포저블이 `tint` 색상 필터(기본값 `LocalContentColor.current`)를
 * 통해 벡터 전체를 다시 색칠하며, 이는 불투명한 모든 픽셀의 색을 대체하고 알파만 보존하므로, 여기의
 * 검은색은 화면에 보이는 색이 아니라 그저 "형태"를 나타내기 위한 편리하고 대비가 높은 대용일 뿐이다.
 */
object TeddIcons {
    /** 뒤로 가기/위로 가기 내비게이션에 쓰이는, 왼쪽을 가리키는 셰브런. */
    val Back: ImageVector by lazy {
        ImageVector.Builder(
            name = "Back",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 12f)
                lineTo(5f, 12f)
                moveTo(11f, 6f)
                lineTo(5f, 12f)
                lineTo(11f, 18f)
            }
        }.build()
    }

    /** 현재 저장되지 않은 페이지를 위한, 열린 북마크 리본. */
    val BookmarkOutline: ImageVector by lazy {
        ImageVector.Builder(
            name = "BookmarkOutline",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 4f)
                lineTo(17f, 4f)
                lineTo(17f, 20f)
                lineTo(12f, 16.5f)
                lineTo(7f, 20f)
                close()
            }
        }.build()
    }

    /** 현재 저장된 페이지를 위한, 채워진 북마크 리본. */
    val BookmarkFilled: ImageVector by lazy {
        ImageVector.Builder(
            name = "BookmarkFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                pathFillType = PathFillType.NonZero,
            ) {
                moveTo(7f, 4f)
                lineTo(17f, 4f)
                lineTo(17f, 20f)
                lineTo(12f, 16.5f)
                lineTo(7f, 20f)
                close()
            }
        }.build()
    }

    /** 시트, 다이얼로그, 오버레이를 닫는 데 쓰이는 "X" 글리프. */
    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 6f)
                lineTo(18f, 18f)
                moveTo(18f, 6f)
                lineTo(6f, 18f)
            }
        }.build()
    }

    /** 보조 액션의 오버플로우 메뉴를 여는, 세로로 쌓인 세 개의 점. */
    val MoreVert: ImageVector by lazy {
        ImageVector.Builder(
            name = "MoreVert",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            repeat(3) { index ->
                val centerY = 6f + index * 6f
                path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                    moveTo(12f, centerY - 1.6f)
                    curveTo(13.1f, centerY - 1.6f, 14f, centerY - 0.7f, 14f, centerY + 0.4f)
                    curveTo(14f, centerY + 1.5f, 13.1f, centerY + 2.4f, 12f, centerY + 2.4f)
                    curveTo(10.9f, centerY + 2.4f, 10f, centerY + 1.5f, 10f, centerY + 0.4f)
                    curveTo(10f, centerY - 0.7f, 10.9f, centerY - 1.6f, 12f, centerY - 1.6f)
                    close()
                }
            }
        }.build()
    }

    /** 이전 페이지나 트랙으로 이동하기 위한, 왼쪽을 가리키는 캐럿. */
    val Previous: ImageVector by lazy {
        ImageVector.Builder(
            name = "Previous",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(15f, 6f)
                lineTo(9f, 12f)
                lineTo(15f, 18f)
            }
        }.build()
    }

    /** 다음 페이지나 트랙으로 이동하기 위한, 오른쪽을 가리키는 캐럿. */
    val Next: ImageVector by lazy {
        ImageVector.Builder(
            name = "Next",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(9f, 6f)
                lineTo(15f, 12f)
                lineTo(9f, 18f)
            }
        }.build()
    }

    /** 재생(예: 자동 스크롤)을 시작하는, 오른쪽을 가리키는 삼각형. */
    val Play: ImageVector by lazy {
        ImageVector.Builder(
            name = "Play",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(8f, 6f)
                lineTo(18f, 12f)
                lineTo(8f, 18f)
                close()
            }
        }.build()
    }

    /** 재생(예: 자동 스크롤)을 일시정지하는, 두 개의 세로 막대. */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.NonZero) {
                moveTo(8f, 6f)
                lineTo(11f, 6f)
                lineTo(11f, 18f)
                lineTo(8f, 18f)
                close()
                moveTo(13f, 6f)
                lineTo(16f, 6f)
                lineTo(16f, 18f)
                lineTo(13f, 18f)
                close()
            }
        }.build()
    }

    /** 리더 자체의 배터리 퍼센트 표시를 위한, 단자 돌기가 있는 배터리 윤곽선. */
    val Battery: ImageVector by lazy {
        ImageVector.Builder(
            name = "Battery",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 7f)
                lineTo(18f, 7f)
                lineTo(18f, 17f)
                lineTo(5f, 17f)
                close()
                moveTo(21f, 10f)
                lineTo(21f, 14f)
            }
        }.build()
    }

    /** 파괴적인 삭제/제거 액션을 위한 휴지통. */
    val Delete: ImageVector by lazy {
        ImageVector.Builder(
            name = "Delete",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4f, 7f)
                lineTo(20f, 7f)
                moveTo(9f, 7f)
                lineTo(9f, 4f)
                lineTo(15f, 4f)
                lineTo(15f, 7f)
                moveTo(7f, 7f)
                lineTo(8f, 20f)
                lineTo(16f, 20f)
                lineTo(17f, 7f)
            }
        }.build()
    }
}
