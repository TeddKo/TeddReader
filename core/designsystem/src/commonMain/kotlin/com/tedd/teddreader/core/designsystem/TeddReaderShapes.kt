package com.tedd.teddreader.core.designsystem

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * 앱 표면에 사용하는 모서리 반경입니다.
 *
 * 리더의 외형이 라이브러리 기본값과 독립적으로 바뀔 수 있도록 Material에서 직접 읽지 않고 앱 고유의
 * 척도로 유지합니다. [toMaterialShapes]는 두 척도를 연결하는 유일한 지점입니다.
 *
 * @property extraSmall 칩이나 배지에 사용하는 반경입니다.
 * @property small 버튼이나 입력 요소에 사용하는 반경입니다.
 * @property medium 카드에 사용하는 반경입니다.
 * @property large 시트나 다이얼로그에 사용하는 반경입니다.
 * @property extraLarge 현재는 [large]와 같은 반경이지만, 카드에 영향을 주지 않고 가장 큰 표면만 별도로
 * 키울 수 있도록 분리해 둔 값입니다.
 */
@Immutable
data class TeddReaderShapes(
    val extraSmall: CornerBasedShape = RoundedCornerShape(4.dp),
    val small: CornerBasedShape = RoundedCornerShape(8.dp),
    val medium: CornerBasedShape = RoundedCornerShape(12.dp),
    val large: CornerBasedShape = RoundedCornerShape(16.dp),
    val extraLarge: CornerBasedShape = RoundedCornerShape(16.dp),
)

/** 호출자가 재정의하지 않을 때 테마가 설치하는 도형 척도입니다. */
val DefaultTeddReaderShapes = TeddReaderShapes()

/**
 * 자체 표면을 그리는 Material 컴포넌트가 라이브러리 기본값 대신 앱의 모서리를 사용하도록 이 척도를
 * Material에 전달합니다.
 *
 * @receiver 앱의 도형 척도입니다.
 * @return `MaterialTheme(shapes = …)`에 전달할 수 있도록 같은 반경을 Material 타입으로 표현한 값입니다.
 */
fun TeddReaderShapes.toMaterialShapes(): Shapes = Shapes(
    extraSmall = extraSmall,
    small = small,
    medium = medium,
    large = large,
    extraLarge = extraLarge,
)
