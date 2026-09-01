package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * 텍스트 페이지에서의 핀치 제스처가 도달할 수 있는, 리더 텍스트 활자 크기의 허용 범위(sp 단위).
 * [readerPinchFontSize]는 모든 핀치 결과를 이 범위 안으로 clamp하며, 활자 크기 옵션 시트가 제공하는
 * 슬라이더 범위로도 겸용되어, 핀치와 설정 시트가 텍스트를 얼마나 크게 또는 작게 만들 수 있는지에 대해
 * 절대 어긋나지 않도록 한다.
 */
internal val ReaderPinchFontSizeRange = 8f..80f

/**
 * visual 페이지(PDF, 이미지, CBZ)의 허용 확대 배율 범위: `1f`는 페이지가 자연스러운 fit-to-viewport
 * 크기로 표시된 상태이며, 이 파일이 계산하는 모든 확대(핀치, 더블탭, 또는 슬라이더로 직접 설정한 값)는
 * 화면에 도달하기 전에 이 범위로 clamp된다.
 */
internal val ReaderPdfZoomRange = 1f..4f

/** visual 페이지가 [ReaderPdfZoomRange]의 최솟값을 넘어 이미 확대된 상태가 아닐 때, 더블탭이 그 페이지를 도약시키는 고정 확대 배율. */
private const val ReaderDoubleTapZoom = 2.5f

/**
 * visual 페이지(PDF, 이미지, CBZ)에 적용된 현재 확대 배율과 이동량을, 둘이 항상 함께 바뀌도록 하나의
 * 값으로 보유한다. 이렇게 하면 호출자가 오래된 이동량에 대해 새 확대 배율을 적용하거나 그 반대로 적용하는
 * 일이 절대 생기지 않는다.
 *
 * @property zoom 확대 배율로, `1f`는 페이지가 자연스러운 fit-to-viewport 크기인 상태다. 항상
 *   [ReaderPdfZoomRange] 안에 있다.
 * @property pan 페이지의 이동량(px), viewport 자체 좌표계 기준 — 확대된 콘텐츠가 중앙에서 얼마나
 *   드래그되어 벗어났는지.
 */
internal data class ReaderPdfTransform(
    val zoom: Float,
    val pan: Offset,
)

/**
 * 텍스트 페이지에서의 핀치 제스처가 끝났을 때 확정되는 활자 크기(sp 단위). [gestureScale]은 제스처가
 * (이전 프레임이 아니라) 시작된 이후로 누적한, 단위 없는 배율이며, 제스처가 시작된 시점의 활자 크기에
 * 적용된 뒤 [ReaderPinchFontSizeRange]로 clamp되고 반올림된다 — 그렇지 않으면 호출자가 활자 크기를
 * 영속화하기 전에 직접 기억해서 적용해야 했을 clamp-and-round와 동일하다.
 *
 * @param startFontSizeSp 핀치 제스처가 시작될 때 적용 중이던 활자 크기(sp).
 * @param gestureScale 제스처가 시작된 이후 누적된 핀치 배율; `1f`는 변화 없음을 뜻한다.
 * @return [ReaderPinchFontSizeRange] 안의 정수인, 새 활자 크기(sp).
 */
internal fun readerPinchFontSize(startFontSizeSp: Int, gestureScale: Float): Int {
    val scaled = startFontSizeSp * gestureScale
    return scaled
        .coerceIn(ReaderPinchFontSizeRange.start, ReaderPinchFontSizeRange.endInclusive)
        .roundToInt()
}

/**
 * visual 페이지의 transform에 확대와 이동 한 증분을 적용하며, 콘텐츠가 스케일되는 동안 제스처의 초점
 * 아래 있는 지점을 화면에서 고정시켜 유지한다 — 항상 viewport 중앙에서 확대하는 대신, 핀치 제스처가 갖출
 * 것으로 기대되는 "손가락 쪽으로 확대" 동작이다. [zoomChange]와 [panChange]는 절대값이 아니라 이전 호출
 * 이후의 델타이며, 이는 포인터 제스처의 `calculateZoom()`/`calculatePan()`이 이벤트마다 보고하는 것과
 * 일치한다.
 *
 * 결과 확대 배율이 정확히 `1f`에 도달하는 순간 이동이 없는 [ReaderPdfTransform]으로 되돌아가므로, 끝까지
 * 축소하면 제스처가 남겨둔 어떤 이동 오프셋이든 상관없이 항상 페이지가 중앙에 놓인다. 그 외의 경우에는
 * 결과 이동량이 clamp되어 페이지가 자기 가장자리 너머로 빈 공간을 보일 만큼 드래그될 수 없다 — 각 축의
 * 최대 이동량은 viewport 절반에 새 확대 배율이 `1f`를 넘은 정도를 곱한 값이다.
 *
 * 유한하지 않은 [centroid]나 [panChange] — 예를 들어 핀치의 마지막 두 손가락 프레임과 첫 한 손가락
 * 프레임 사이처럼, 제스처 감지기가 일시적으로 보고할 수 있는 상태 — 는 각각 viewport 중앙과 이동 없음으로
 * 대체되며, `NaN`이 transform으로 전파되도록 두지 않는다.
 *
 * @param current 이 증분이 적용되기 전의 transform.
 * @param zoomChange 이전 호출 이후의 확대 배율; 이동만 있는 갱신이면 `1f`.
 * @param panChange 이전 호출 이후의 이동 델타(px).
 * @param centroid 제스처의 초점(px), viewport 자체 좌표계 기준 — 확대가 그 지점을 중심으로 적용된다.
 * @param viewportSize 페이지가 렌더링되는 영역의 크기(px). viewport 중앙을 찾는 데도, 이동 clamp 경계를
 *   계산하는 데도 쓰인다.
 * @return 갱신되고 경계로 clamp된 transform.
 */
internal fun readerPdfTransform(
    current: ReaderPdfTransform,
    zoomChange: Float,
    panChange: Offset,
    centroid: Offset,
    viewportSize: IntSize,
): ReaderPdfTransform {
    val newZoom = (current.zoom * zoomChange)
        .coerceIn(ReaderPdfZoomRange.start, ReaderPdfZoomRange.endInclusive)

    if (newZoom == 1f) {
        return ReaderPdfTransform(zoom = 1f, pan = Offset.Zero)
    }

    val ratio = newZoom / current.zoom
    val center = Offset(
        x = viewportSize.width / 2f,
        y = viewportSize.height / 2f,
    )
    val safeCentroid = if (centroid.x.isFinite() && centroid.y.isFinite()) centroid else center
    val safePanChange = if (panChange.x.isFinite() && panChange.y.isFinite()) panChange else Offset.Zero
    val focalOffset = safeCentroid - center
    val unclampedPan = current.pan * ratio + focalOffset * (1f - ratio) + safePanChange
    val maxPanX = viewportSize.width / 2f * (newZoom - 1f)
    val maxPanY = viewportSize.height / 2f * (newZoom - 1f)

    return ReaderPdfTransform(
        zoom = newZoom,
        pan = Offset(
            x = unclampedPan.x.coerceIn(-maxPanX, maxPanX),
            y = unclampedPan.y.coerceIn(-maxPanY, maxPanY),
        ),
    )
}

/**
 * 실시간 제스처가 만들어낸 것이 아닌 [zoom]/[pan] 쌍 — 예를 들어 view 옵션 시트의 visual-zoom
 * 슬라이더가 직접 설정한 값 — 에 대해 유효하고 경계 안에 있는 [ReaderPdfTransform]을 다시 유도한다.
 * 확대·이동 델타 없이, viewport 자체 중앙을 초점으로 삼아 [readerPdfTransform]에 위임하므로, 직접
 * 설정된 확대 배율도 핀치 제스처가 적용했을 것과 정확히 같은 가장자리 clamp를 적용받는다.
 *
 * @param zoom 적용할 확대 배율로, 사용 전에 [ReaderPdfZoomRange]로 clamp된다.
 * @param pan [zoom]에 맞춰 조정할 이동량(px), viewport 자체 좌표계 기준.
 * @param viewportSize 페이지가 렌더링되는 영역의 크기(px).
 * @return [zoom]에 대해 이동량이 경계 안에 있음이 보장되는 transform.
 */
internal fun readerClampedPdfTransform(
    zoom: Float,
    pan: Offset,
    viewportSize: IntSize,
): ReaderPdfTransform = readerPdfTransform(
    current = ReaderPdfTransform(
        zoom = zoom.coerceIn(ReaderPdfZoomRange.start, ReaderPdfZoomRange.endInclusive),
        pan = pan,
    ),
    zoomChange = 1f,
    panChange = Offset.Zero,
    centroid = Offset(
        x = viewportSize.width / 2f,
        y = viewportSize.height / 2f,
    ),
    viewportSize = viewportSize,
)

/**
 * visual 페이지에서 더블탭이 도약해야 할 transform. 토글처럼 동작한다: 페이지가 이미
 * [ReaderPdfZoomRange]의 최솟값을 넘어 확대되어 있다면 곧바로 transform 없는 `1f`/이동 없음 상태로
 * 되돌리고, 그렇지 않으면 탭한 지점을 중심으로 [ReaderDoubleTapZoom]까지 확대한다. 이는 핀치 제스처가
 * 쓰는 것과 같은 [readerPdfTransform] 경로를 통해 이루어지므로, 페이지가 커지는 동안 탭한 지점이 손가락
 * 아래 고정된 채로 유지된다.
 *
 * @param current 더블탭이 일어났을 때 적용 중이던 transform.
 * @param tapPosition 탭 위치(px), viewport 자체 좌표계 기준 — 확대 케이스가 그 지점을 중심으로 확대한다.
 * @param viewportSize 페이지가 렌더링되는 영역의 크기(px).
 * @return [current]가 이미 확대되어 있었는지에 따라, 리셋되거나 확대된 transform.
 */
internal fun readerDoubleTapVisualTransform(
    current: ReaderPdfTransform,
    tapPosition: Offset,
    viewportSize: IntSize,
): ReaderPdfTransform = if (current.zoom > ReaderPdfZoomRange.start) {
    ReaderPdfTransform(zoom = ReaderPdfZoomRange.start, pan = Offset.Zero)
} else {
    readerPdfTransform(
        current = current,
        zoomChange = ReaderDoubleTapZoom / current.zoom,
        panChange = Offset.Zero,
        centroid = tapPosition,
        viewportSize = viewportSize,
    )
}

/**
 * 리더의 결합된 핀치/이동 제스처를 페이지 콘텐츠에 설치한다: 두 손가락 핀치는 텍스트 페이지에서는 텍스트
 * 활자 크기를 조절하고 visual 페이지에서는 확대하며, visual 페이지가 확대된 뒤에는 한 손가락으로 그것을
 * 이동시킬 수 있다. 두 모드([isVisualMode]가 어느 쪽인지 정한다)는 별개의 `pointerInput` 블록 두 개가
 * 아니라 하나의 제스처 루프를 공유하는데, 어떤 페이지에서든 두 모드는 상호 배타적이며 호출자는 항상 둘 중
 * 하나만 활성화되길 원하기 때문이다.
 *
 * 모든 파라미터는 직접 읽는 대신 [rememberUpdatedState]를 통해 캡처된다. 아래의 `pointerInput(Unit)`
 * 제스처 감지 코루틴은 상수 `Unit`을 키로 삼기 때문에 recomposition을 거쳐도 절대 재시작되지 않으며 —
 * 이런 간접 참조가 없으면 composable이 화면에 남아 있는 동안 첫 실행 시 캡처된 값들을 계속 관찰하게 될
 * 것이다.
 *
 * 이것이 `Modifier.composed { }` 블록이 아니라 `@Composable` modifier factory인 이유: `composed`는
 * 자신의 내용을 modifier 비교에서 숨기므로, 노드 재사용에 참여하는 대신 페이지 콘텐츠가
 * recomposition될 때마다 전체 구간이 다시 구체화됐을 것이다. 위의 캡처들이 composition 접근이 필요한
 * 유일한 이유이며, 평범한 composable 함수는 불투명한 래퍼 없이 그것을 제공한다. 캡처들이 [enabled] 검사
 * 이전에 확립되어 있음에 주의한다. composable은 `remember` 호출을 조건부로 만들면 안 되기 때문이며,
 * 그렇지 않으면 [enabled]가 바뀔 때마다 그 이후의 모든 composition 슬롯이 밀리게 된다.
 *
 * 핀치가 시작되면 항상 자동 스크롤을 먼저 끈다([onAutoScrollEnabledChange]). 리더가 활발히 크기를
 * 조절하거나 확대하고 있는 페이지 아래에서 스크롤이 진행되는 것은 쓸모가 없기 때문이다. 그리고 텍스트
 * 핀치의 활자 크기 변경은 제스처가 끝나야만 확정되며([onTextFontSizeCommit]), 진행 중일 때는 호출자에게
 * 실시간 미리보기 배율만 보여서([onTextGestureScaleChange]) 텍스트 레이아웃이 매 프레임 재측정되지
 * 않도록 한다.
 *
 * @receiver 이 제스처가 붙는 페이지 콘텐츠 modifier.
 * @param enabled 이 제스처가 아예 참여할지 여부. 아직 어떤 페이지도 페이지 나누기가 되지 않아 화면에
 *   확대하거나 이동할 것이 없는 동안에는 리더가 이를 끈다.
 * @param viewportSize 페이지 콘텐츠 영역의 크기(px). [readerPdfTransform]과 정확히 같은 방식으로 이동
 *   경계와 확대 초점을 계산하는 데 쓰인다.
 * @param isVisualMode visual 페이지(PDF, 이미지, CBZ)를 확대/이동하려면 true; 대신 텍스트 활자 크기를
 *   조절하려면 false.
 * @param textStartFontSizeSp 텍스트 핀치 제스처가 시작될 때 적용 중인 활자 크기(sp) — 기준이 되는 값에서
 *   [readerPinchFontSize]가 배율을 적용한다.
 * @param pdfTransform visual 페이지의 현재 확대/이동 상태로, 각 제스처가 시작될 때 읽혀 핀치나 이동
 *   증분이 그 위에 적용되는 기준이 된다.
 * @param isAutoScrollEnabled 자동 스크롤이 현재 켜져 있는지 여부. 핀치가 시작되는 순간에 확인되어, 실제로
 *   꺼야 할 때만([onAutoScrollEnabledChange]) 꺼진다.
 * @param onAutoScrollEnabledChange 자동 스크롤을 끈다; [isAutoScrollEnabled]가 true인 상태에서 두 손가락
 *   핀치가 시작되는 순간 `false`로 호출된다.
 * @param onGestureActiveChange 이 제스처가 현재 포인터 입력을 소유하고 있는지 보고하여, 호출자가 그동안
 *   다른 제스처(예: 페이지 넘김 탭)를 억제할 수 있도록 한다.
 * @param onTextGestureScaleChange 텍스트 핀치 도중의, 실시간이며 아직 확정되지 않은 텍스트 배율. 호출자가
 *   페이지를 재측정하지 않고 미리 볼 수 있도록 한다.
 * @param onTextFontSizeCommit 텍스트 핀치가 [textStartFontSizeSp]와 다른 크기로 끝났을 때의 최종 활자
 *   크기(sp).
 * @param onPdfTransformChange visual 페이지의 갱신된 확대/이동 상태로, 제스처가 그것을 바꾸는 매 프레임
 *   호출된다.
 * @return 제스처가 붙은 이 modifier, 또는 [enabled]가 false이면 변경되지 않은 그대로.
 */
@Composable
internal fun Modifier.readerPinchZoomGesture(
    enabled: Boolean,
    viewportSize: IntSize,
    isVisualMode: Boolean,
    textStartFontSizeSp: Int,
    pdfTransform: ReaderPdfTransform,
    isAutoScrollEnabled: Boolean,
    onAutoScrollEnabledChange: (Boolean) -> Unit,
    onGestureActiveChange: (Boolean) -> Unit,
    onTextGestureScaleChange: (Float) -> Unit,
    onTextFontSizeCommit: (Int) -> Unit,
    onPdfTransformChange: (ReaderPdfTransform) -> Unit,
): Modifier {
    val latestViewportSize by rememberUpdatedState(viewportSize)
    val latestIsVisualMode by rememberUpdatedState(isVisualMode)
    val latestTextStartFontSizeSp by rememberUpdatedState(textStartFontSizeSp)
    val latestPdfTransform by rememberUpdatedState(pdfTransform)
    val latestIsAutoScrollEnabled by rememberUpdatedState(isAutoScrollEnabled)
    val latestOnAutoScrollEnabledChange by rememberUpdatedState(onAutoScrollEnabledChange)
    val latestOnGestureActiveChange by rememberUpdatedState(onGestureActiveChange)
    val latestOnTextGestureScaleChange by rememberUpdatedState(onTextGestureScaleChange)
    val latestOnTextFontSizeCommit by rememberUpdatedState(onTextFontSizeCommit)
    val latestOnPdfTransformChange by rememberUpdatedState(onPdfTransformChange)

    if (!enabled) return this

    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var gestureOwned = false
            var gestureActive = false
            var pinchStarted = false
            var textGestureScale = 1f
            var pdfGestureTransform = latestPdfTransform
            val textFontSizeAtGestureStart = latestTextStartFontSizeSp

            fun startGesture() {
                if (!gestureActive) {
                    latestOnGestureActiveChange(true)
                    gestureActive = true
                }
            }

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressedChanges = event.changes.filter { it.pressed }
                val pressedCount = pressedChanges.size
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)

                if (pressedCount >= 2 && !pinchStarted) {
                    pinchStarted = true
                    gestureOwned = true
                    startGesture()
                    if (latestIsAutoScrollEnabled) {
                        latestOnAutoScrollEnabledChange(false)
                    }
                }

                if (!gestureOwned && latestIsVisualMode && latestPdfTransform.zoom > 1f && pressedCount == 1 && panChange != Offset.Zero) {
                    gestureOwned = true
                    startGesture()
                }

                if (gestureOwned) {
                    if (latestIsVisualMode) {
                        val nextTransform = if (pressedCount >= 2 || pdfGestureTransform.zoom > 1f) {
                            readerPdfTransform(
                                current = pdfGestureTransform,
                                zoomChange = if (pressedCount >= 2) zoomChange else 1f,
                                panChange = panChange,
                                centroid = centroid,
                                viewportSize = latestViewportSize,
                            )
                        } else {
                            pdfGestureTransform
                        }
                        if (nextTransform != pdfGestureTransform) {
                            pdfGestureTransform = nextTransform
                            latestOnPdfTransformChange(nextTransform)
                        }
                    } else if (pressedCount >= 2) {
                        val minScale = ReaderPinchFontSizeRange.start / textFontSizeAtGestureStart
                        val maxScale = ReaderPinchFontSizeRange.endInclusive / textFontSizeAtGestureStart
                        textGestureScale = (textGestureScale * zoomChange).coerceIn(minScale, maxScale)
                        latestOnTextGestureScaleChange(textGestureScale)
                    }

                    event.changes.forEach { change ->
                        if (!change.isConsumed) {
                            change.consume()
                        }
                    }
                }

                if (pressedCount == 0) break
            }

            if (!latestIsVisualMode) {
                latestOnTextGestureScaleChange(1f)
                if (pinchStarted) {
                    val committedFontSize = readerPinchFontSize(
                        startFontSizeSp = textFontSizeAtGestureStart,
                        gestureScale = textGestureScale,
                    )
                    if (committedFontSize != textFontSizeAtGestureStart) {
                        latestOnTextFontSizeCommit(committedFontSize)
                    }
                }
            }

            if (gestureActive) {
                latestOnGestureActiveChange(false)
            }
        }
    }
}
