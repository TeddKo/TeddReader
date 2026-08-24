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
 * A dp size in pixels at the current density.
 *
 * One of the conversions this file gathers so no screen open-codes a `* density` and gets it wrong on the
 * one device that differs. The `Dp`/`Float` receivers here are composable because they read the current
 * density; the `Density` receivers below are not, so layout and draw code that already holds a density can
 * convert without composing.
 *
 * @receiver a size in dp.
 * @return the same size in pixels.
 */
@Composable
fun Dp.dpToPx(): Float = value * LocalDensity.current.density

/**
 * @receiver a size in dp.
 * @return the same size in sp, which shrinks it when the reader has enlarged system text — use for anything
 * that has to line up with type.
 */
@Composable
fun Dp.dpToSp(): TextUnit = LocalDensity.current.dpToSp(this)

/**
 * @receiver a size in pixels.
 * @return the same size in dp at the current density.
 */
@Composable
fun Float.pxToDp(): Dp = (this / LocalDensity.current.density).dp

/**
 * @receiver a size in pixels.
 * @return the same size in sp, undoing both the density and the font scale.
 */
@Composable
fun Float.pxToSp(): TextUnit = LocalDensity.current.pxToSp(this)

/**
 * @receiver the density to convert with.
 * @param dp the size to convert.
 * @return the size in sp, so a dp value tracks the reader's text-size setting.
 */
fun Density.dpToSp(dp: Dp): TextUnit = (dp.value / fontScale).sp

/**
 * @receiver the density to convert with.
 * @param px the size to convert.
 * @return the size in sp.
 */
fun Density.pxToSp(px: Float): TextUnit = (px / (density * fontScale)).sp

/**
 * @receiver the density to convert with.
 * @param dpSize the size to convert.
 * @return the size in whole pixels, rounded — what a layout node's constraints take.
 */
fun Density.toIntSize(dpSize: DpSize): IntSize =
    IntSize(dpSize.width.roundToPx(), dpSize.height.roundToPx())

/**
 * @receiver the density to convert with.
 * @param dpSize the size to convert.
 * @return the size in pixels, or `Size.Unspecified` when [dpSize] is itself unspecified, so "unknown"
 * survives the conversion instead of becoming zero.
 */
fun Density.toSize(dpSize: DpSize): Size =
    if (dpSize.isSpecified) Size(dpSize.width.toPx(), dpSize.height.toPx()) else Size.Unspecified

/**
 * @receiver the density to convert with.
 * @param size the pixel size to convert.
 * @return the size in dp, or `DpSize.Unspecified` when [size] is unspecified.
 */
fun Density.toDpSize(size: Size): DpSize =
    if (size.isSpecified) DpSize(size.width.toDp(), size.height.toDp()) else DpSize.Unspecified

/**
 * @receiver the density to convert with.
 * @param intSize the whole-pixel size to convert.
 * @return the size in dp.
 */
fun Density.toDpSize(intSize: IntSize): DpSize =
    DpSize(intSize.width.toDp(), intSize.height.toDp())

/**
 * @receiver the density to convert with.
 * @param dpOffset the offset to convert.
 * @return the offset in whole pixels, rounded.
 */
fun Density.toIntOffset(dpOffset: DpOffset): IntOffset =
    IntOffset(dpOffset.x.roundToPx(), dpOffset.y.roundToPx())

/**
 * @receiver the density to convert with.
 * @param dpOffset the offset to convert.
 * @return the offset in pixels, or `Offset.Unspecified` when [dpOffset] is unspecified.
 */
fun Density.toOffset(dpOffset: DpOffset): Offset =
    if (dpOffset.isSpecified) Offset(dpOffset.x.toPx(), dpOffset.y.toPx()) else Offset.Unspecified

/**
 * @receiver the density to convert with.
 * @param offset the pixel offset to convert.
 * @return the offset in dp, or `DpOffset.Unspecified` when [offset] is unspecified.
 */
fun Density.toDpOffset(offset: Offset): DpOffset =
    if (offset.isSpecified) DpOffset(offset.x.toDp(), offset.y.toDp()) else DpOffset.Unspecified

/**
 * @receiver the density to convert with.
 * @param intOffset the whole-pixel offset to convert.
 * @return the offset in dp.
 */
fun Density.toDpOffset(intOffset: IntOffset): DpOffset =
    DpOffset(intOffset.x.toDp(), intOffset.y.toDp())
