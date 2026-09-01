package com.tedd.teddreader.core.ui.extension

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 현재 density에서의 dp 크기를 픽셀로 변환한 값.
 *
 * 이 파일이 모아 둔 변환 함수 중 하나로, 어떤 화면도 `* density`를 직접 코드로 풀어써서 그것이 다른
 * 딱 한 기기에서 틀리는 일이 없게 한다. 여기의 `Dp`/`Float` 리시버는 현재 density를 읽으므로
 * composable이고, 아래의 `Density` 리시버는 그렇지 않아, 이미 density를 가지고 있는 레이아웃/드로우
 * 코드는 컴포즈하지 않고도 변환할 수 있다.
 *
 * @receiver dp 단위의 크기.
 * @return 픽셀 단위의 같은 크기.
 */
@Composable
fun Dp.dpToPx(): Float = value * LocalDensity.current.density

/**
 * @receiver dp 단위의 크기.
 * @return sp 단위의 같은 크기. 사용자가 시스템 글자 크기를 키운 경우 그만큼 줄어들며, 활자와 맞춰야
 * 하는 값에 사용한다.
 */
@Composable
fun Dp.dpToSp(): TextUnit = LocalDensity.current.dpToSp(this)

/**
 * @receiver 픽셀 단위의 크기.
 * @return 현재 density에서의 dp 단위 같은 크기.
 */
@Composable
fun Float.pxToDp(): Dp = (this / LocalDensity.current.density).dp

/**
 * @receiver 픽셀 단위의 크기.
 * @return density와 글꼴 배율을 모두 되돌린, sp 단위의 같은 크기.
 */
@Composable
fun Float.pxToSp(): TextUnit = LocalDensity.current.pxToSp(this)

/**
 * @receiver 변환에 사용할 density.
 * @param dp 변환할 크기.
 * @return sp 단위의 크기. dp 값이 사용자의 글자 크기 설정을 따라가게 한다.
 */
fun Density.dpToSp(dp: Dp): TextUnit = (dp.value / fontScale).sp

/**
 * @receiver 변환에 사용할 density.
 * @param px 변환할 크기.
 * @return sp 단위의 크기.
 */
fun Density.pxToSp(px: Float): TextUnit = (px / (density * fontScale)).sp

/**
 * @receiver 변환에 사용할 density.
 * @param dpSize 변환할 크기.
 * @return 반올림된 정수 픽셀 단위의 크기 — 레이아웃 노드의 제약이 받는 형태.
 */
fun Density.toIntSize(dpSize: DpSize): IntSize =
    IntSize(dpSize.width.roundToPx(), dpSize.height.roundToPx())

/**
 * @receiver 변환에 사용할 density.
 * @param dpSize 변환할 크기.
 * @return 픽셀 단위의 크기. [dpSize] 자체가 unspecified이면 `Size.Unspecified`를 반환하여, "알 수
 * 없음"이 0이 되어 버리지 않고 변환을 거쳐도 그대로 남는다.
 */
fun Density.toSize(dpSize: DpSize): Size =
    if (dpSize.isSpecified) Size(dpSize.width.toPx(), dpSize.height.toPx()) else Size.Unspecified

/**
 * @receiver 변환에 사용할 density.
 * @param size 변환할 픽셀 크기.
 * @return dp 단위의 크기. [size]가 unspecified이면 `DpSize.Unspecified`.
 */
fun Density.toDpSize(size: Size): DpSize =
    if (size.isSpecified) DpSize(size.width.toDp(), size.height.toDp()) else DpSize.Unspecified

/**
 * @receiver 변환에 사용할 density.
 * @param intSize 변환할 정수 픽셀 크기.
 * @return dp 단위의 크기.
 */
fun Density.toDpSize(intSize: IntSize): DpSize =
    DpSize(intSize.width.toDp(), intSize.height.toDp())

/**
 * @receiver 변환에 사용할 density.
 * @param dpOffset 변환할 오프셋.
 * @return 반올림된 정수 픽셀 단위의 오프셋.
 */
fun Density.toIntOffset(dpOffset: DpOffset): IntOffset =
    IntOffset(dpOffset.x.roundToPx(), dpOffset.y.roundToPx())

/**
 * @receiver 변환에 사용할 density.
 * @param dpOffset 변환할 오프셋.
 * @return 픽셀 단위의 오프셋. [dpOffset]이 unspecified이면 `Offset.Unspecified`.
 */
fun Density.toOffset(dpOffset: DpOffset): Offset =
    if (dpOffset.isSpecified) Offset(dpOffset.x.toPx(), dpOffset.y.toPx()) else Offset.Unspecified

/**
 * @receiver 변환에 사용할 density.
 * @param offset 변환할 픽셀 오프셋.
 * @return dp 단위의 오프셋. [offset]이 unspecified이면 `DpOffset.Unspecified`.
 */
fun Density.toDpOffset(offset: Offset): DpOffset =
    if (offset.isSpecified) DpOffset(offset.x.toDp(), offset.y.toDp()) else DpOffset.Unspecified

/**
 * @receiver 변환에 사용할 density.
 * @param intOffset 변환할 정수 픽셀 오프셋.
 * @return dp 단위의 오프셋.
 */
fun Density.toDpOffset(intOffset: IntOffset): DpOffset =
    DpOffset(intOffset.x.toDp(), intOffset.y.toDp())
