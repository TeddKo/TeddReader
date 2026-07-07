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

@Composable
fun Dp.dpToPx(): Float = value * LocalDensity.current.density

@Composable
fun Dp.dpToSp(): TextUnit = LocalDensity.current.dpToSp(this)

@Composable
fun Float.pxToDp(): Dp = (this / LocalDensity.current.density).dp

@Composable
fun Float.pxToSp(): TextUnit = LocalDensity.current.pxToSp(this)

fun Density.dpToSp(dp: Dp): TextUnit = (dp.value / fontScale).sp

fun Density.pxToSp(px: Float): TextUnit = (px / (density * fontScale)).sp

fun Density.toIntSize(dpSize: DpSize): IntSize =
    IntSize(dpSize.width.roundToPx(), dpSize.height.roundToPx())

fun Density.toSize(dpSize: DpSize): Size =
    if (dpSize.isSpecified) Size(dpSize.width.toPx(), dpSize.height.toPx()) else Size.Unspecified

fun Density.toDpSize(size: Size): DpSize =
    if (size.isSpecified) DpSize(size.width.toDp(), size.height.toDp()) else DpSize.Unspecified

fun Density.toDpSize(intSize: IntSize): DpSize =
    DpSize(intSize.width.toDp(), intSize.height.toDp())

fun Density.toIntOffset(dpOffset: DpOffset): IntOffset =
    IntOffset(dpOffset.x.roundToPx(), dpOffset.y.roundToPx())

fun Density.toOffset(dpOffset: DpOffset): Offset =
    if (dpOffset.isSpecified) Offset(dpOffset.x.toPx(), dpOffset.y.toPx()) else Offset.Unspecified

fun Density.toDpOffset(offset: Offset): DpOffset =
    if (offset.isSpecified) DpOffset(offset.x.toDp(), offset.y.toDp()) else DpOffset.Unspecified

fun Density.toDpOffset(intOffset: IntOffset): DpOffset =
    DpOffset(intOffset.x.toDp(), intOffset.y.toDp())
