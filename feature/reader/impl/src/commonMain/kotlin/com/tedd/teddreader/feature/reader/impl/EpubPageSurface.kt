package com.tedd.teddreader.feature.reader.impl

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.maxBitmapSize
import coil3.size.Size
import com.tedd.teddreader.core.common.model.ReaderBlock
import com.tedd.teddreader.core.common.model.ReaderBlockKind
import com.tedd.teddreader.core.common.model.ReaderBlockStyle
import com.tedd.teddreader.core.common.model.ReaderBorder
import com.tedd.teddreader.core.common.model.ReaderBoxStyle
import com.tedd.teddreader.core.common.model.ReaderColor
import com.tedd.teddreader.core.common.model.ReaderDarkTextArgb
import com.tedd.teddreader.core.common.model.ReaderFloat
import com.tedd.teddreader.core.common.model.ReaderLightTextArgb
import com.tedd.teddreader.core.common.model.ReaderStyle
import com.tedd.teddreader.core.common.model.ReaderThemeMode
import com.tedd.teddreader.core.common.model.layoutKey
import com.tedd.teddreader.core.designsystem.readerTextStyle
import com.tedd.teddreader.core.designsystem.teddReaderColors
import com.tedd.teddreader.core.designsystem.teddReaderTypography
import com.tedd.teddreader.core.designsystem.toColor
import com.tedd.teddreader.core.ui.component.TeddDivider
import com.tedd.teddreader.core.ui.component.TeddText
import com.tedd.teddreader.core.ui.generated.resources.Res
import com.tedd.teddreader.core.ui.generated.resources.visual_page_unavailable
import com.tedd.teddreader.core.ui.reader.ReaderContainerDecoration
import com.tedd.teddreader.core.ui.reader.ReaderFloatContent
import com.tedd.teddreader.core.ui.reader.ReaderPlaceholder
import com.tedd.teddreader.core.ui.reader.buildReaderSemanticText
import com.tedd.teddreader.core.ui.reader.readerFloatTextFitter
import com.tedd.teddreader.core.ui.reader.readerLayoutInputs
import com.tedd.teddreader.core.ui.reader.readerReferencedFontHrefs
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import org.jetbrains.compose.resources.stringResource

/**
 * page breaker가 측정한 것과 동일한 semantic text로 EPUB 텍스트 페이지 한 장을 그린다.
 *
 * 페이지 서피스는 breaker가 사용한 것과 같은 text style, 폰트 패밀리, 너비, float fitting 콜백으로 semantic
 * text를 다시 구성한 뒤, 실제로 그리기 위해 Compose가 그 결과를 한 번 레이아웃하도록 한다. 컨테이너 배경과
 * 테두리는 최종 텍스트 레이아웃 geometry로부터 그려져서, 페이지 장식이 글리프가 차지하는 것과 같은 줄을
 * 따라가도록 한다.
 *
 * @param page 그릴, 이미 분할된 페이지 콘텐츠. 내장 이미지 바이트와 실패 상태를 포함한다.
 * @param style 이 페이지 조각이 측정된 기준 style로, 테마 모드와 사용자 폰트 오버라이드를 포함한다 — 색상은
 * 어차피 실시간 style로부터 들어온다. 이 파라미터에 실제로 어떤 style이 도달하는지 결정하는 로직은
 * `ReaderScreen.kt`의 `ReaderPagePane`를 참고한다.
 * @param embeddedFontFamiliesByHref EPUB href를 키로 하는, 해석된 내장 폰트 패밀리. page breaker와 공유되어
 * 측정과 렌더링이 어긋나지 않도록 한다.
 * @param modifier 페이지 루트에 적용된다.
 */
@Composable
internal fun EpubPageSurface(
    page: ReaderPageUi,
    style: ReaderStyle,
    embeddedFontFamiliesByHref: ImmutableMap<String, FontFamily> = persistentMapOf(),
    modifier: Modifier = Modifier,
) {
    val plateBlock = epubFullPagePlate(text = page.text, blocks = page.blocks)
    val readerTextStyle = epubPageTextStyle(page, style)
    val baseTextColor = readerTextStyle.color
    val publisherColorsEnabled = style.themeMode == ReaderThemeMode.PUBLISHER
    if (plateBlock != null) {
        EpubImageBox(
            imageBytes = plateBlock.imageHref?.let(page.embeddedImages::get),
            imageCacheKey = epubImageMemoryCacheKey(page.documentUri, plateBlock.imageHref),
            label = plateBlock.label,
            isFailed = plateBlock.imageHref != null && plateBlock.imageHref in page.failedEmbeddedImageHrefs,
            boxStyle = plateBlock.style?.boxStyle,
            currentColor = plateBlock.style?.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor,
            usePublisherColors = publisherColorsEnabled,
            modifier = modifier,
        )
        return
    }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 0)
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        // breaker도 사용하는 바로 그 유도값이다 — 구조체도 숫자도 같아서, 페이지는 측정될 때와
        // 정확히 같은 값으로 그려진다.
        val inputs = remember(style.layoutKey(), widthPx, heightPx, density, embeddedFontFamiliesByHref) {
            readerLayoutInputs(
                style = style,
                widthPx = widthPx.toInt(),
                heightPx = heightPx.toInt(),
                density = density,
                embeddedFontFamiliesByHref = embeddedFontFamiliesByHref,
            )
        }
        val fontPx = inputs.fontPx
        val floatFitter = remember(measurer, inputs, publisherColorsEnabled) {
            readerFloatTextFitter(
                measurer = measurer,
                inputs = inputs,
                publisherColorsEnabled = publisherColorsEnabled,
            )
        }
        val semanticText = remember(page.text, page.blocks, page.textRange, inputs, publisherColorsEnabled, floatFitter, style.layoutKey()) {
            buildReaderSemanticText(
                text = page.text,
                blocks = page.blocks,
                range = page.textRange ?: com.tedd.teddreader.core.common.model.TextRange(0, page.text.length.toLong()),
                lineWidthEm = inputs.lineWidthEm,
                maxHeightEm = inputs.maxHeightEm,
                emInPx = inputs.emInPx,
                embeddedFontFamiliesByHref = inputs.embeddedFontFamiliesByHref,
                publisherColorsEnabled = publisherColorsEnabled,
                publisherFontsEnabled = inputs.publisherFontsEnabled,
                floatTextFitter = floatFitter,
                lineHeightMultiplier = inputs.lineHeightMultiplier,
                baseFontWeight = inputs.fontWeight,
            )
        }
        val inlineContent = remember(semanticText.placeholders, page.documentUri, page.embeddedImages, page.failedEmbeddedImageHrefs, readerTextStyle, baseTextColor, publisherColorsEnabled) {
            semanticText.placeholders.associate { placeholder ->
                placeholder.id to InlineTextContent(placeholder.placeholder) {
                    EpubInlinePlaceholder(
                        placeholder = placeholder,
                        imageBytes = placeholder.href?.let(page.embeddedImages::get),
                        imageCacheKey = epubImageMemoryCacheKey(page.documentUri, placeholder.href),
                        isFailed = placeholder.href != null && placeholder.href in page.failedEmbeddedImageHrefs,
                        textStyle = readerTextStyle,
                        baseTextColor = baseTextColor,
                        publisherColorsEnabled = publisherColorsEnabled,
                    )
                }
            }
        }
        var textLayout by remember(semanticText.annotatedString) { mutableStateOf<TextLayoutResult?>(null) }

        Layout(
            content = {
                BasicText(
                    text = semanticText.annotatedString,
                    style = readerTextStyle,
                    inlineContent = inlineContent,
                    onTextLayout = { textLayout = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            textLayout?.let { layout ->
                                drawContainerDecorations(
                                    layout = layout,
                                    decorations = semanticText.containerDecorations,
                                    baseTextColor = baseTextColor,
                                    publisherColorsEnabled = publisherColorsEnabled,
                                    emPx = fontPx,
                                )
                            }
                        },
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    semanticText.containerDecorations
                        .filter(ReaderContainerDecoration::isPageContainer)
                        .forEach { decoration ->
                            drawReaderBoxBackground(
                                boxStyle = decoration.boxStyle,
                                rect = Rect(Offset.Zero, size),
                                usePublisherColors = publisherColorsEnabled,
                            )
                            drawReaderBoxBorders(
                                boxStyle = decoration.boxStyle,
                                rect = Rect(Offset.Zero, size),
                                currentColor = decoration.foregroundColor?.toColor() ?: baseTextColor,
                                usePublisherColors = publisherColorsEnabled,
                                drawTop = decoration.startsHere,
                                drawBottom = decoration.endsHere,
                            )
                        }
                },
        ) { measurables, constraints ->
            val placeable = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            val pageHeight = constraints.maxHeight
            val top = if (page.isSectionTail) ((pageHeight - placeable.height) / 2).coerceAtLeast(0) else 0
            layout(constraints.maxWidth, pageHeight) { placeable.place(0, top) }
        }
    }
}

/**
 * EPUB 페이지가 첫 측정 페이지 나누기를 허용할 만큼 충분한 내장 폰트 상태를 갖췄는지 여부.
 *
 * 사용자가 선택한 리더 폰트는 퍼블리셔 폰트를 아예 우회하므로 기다릴 필요가 없다. 그 외의 경우에는 페이지가
 * 참조하는 모든 폰트 href가 breaker의 측정을 허용하기 전에 성공적으로 해석되었거나 알려진 실패 상태에
 * 도달해 있어야 한다.
 */
internal fun canMeasureEpubPage(
    page: ReaderPageUi,
    style: ReaderStyle,
    resolvedFontFamiliesByHref: Map<String, FontFamily> = emptyMap(),
    failedResolvedFontHrefs: Set<String> = emptySet(),
): Boolean {
    if (style.fontFamilyName != null) return true
    val referenced = readerReferencedFontHrefs(page.blocks)
    return referenced.all { href ->
        href in resolvedFontFamiliesByHref ||
            href in page.failedEmbeddedFontHrefs ||
            href in failedResolvedFontHrefs
    }
}

/**
 * EPUB 페이지의 인라인 placeholder가 [buildReaderSemanticText]가 예약해 둔 자리에서 실제로 해석되는
 * composable — 이미지, floated 이미지와 그 안에 중첩된 텍스트, 구분선, 또는 자체 시각 콘텐츠가 없는
 * placeholder 종류라면 아무것도 그리지 않는다.
 *
 * @param placeholder [buildReaderSemanticText]가 만들어낸 placeholder의 종류, 라벨, float 메타데이터,
 * 상속된 색상.
 * @param imageBytes 이 placeholder의 인코딩된 이미지 바이트. 종류가 이미지이고 바이트를 사용할 수 있을 때.
 * @param imageCacheKey Coil이 디코딩된 이미지를 재사용하는 데 쓰는, 문서와 href로 결정되는 안정적인 식별자.
 * @param isFailed 이 placeholder의 이미지가 이전에 디코딩에 실패한 적이 있는지 여부.
 * @param textStyle 중첩된 float 텍스트가 그려야 할, 해석이 끝난 text style.
 * @param baseTextColor 퍼블리셔 색상이 꺼져 있을 때 사용하는 리더 테마의 기본 텍스트 색상.
 * @param publisherColorsEnabled 이 페이지에서 퍼블리셔 색상을 존중할지 여부.
 */
@Composable
private fun EpubInlinePlaceholder(
    placeholder: ReaderPlaceholder,
    imageBytes: ByteArray?,
    imageCacheKey: String?,
    isFailed: Boolean,
    textStyle: TextStyle,
    baseTextColor: Color,
    publisherColorsEnabled: Boolean,
) {
    val floatContent = placeholder.floatContent
    when {
        floatContent != null -> EpubFloatPlaceholder(
            floatContent = floatContent,
            imageBytes = imageBytes,
            imageCacheKey = imageCacheKey,
            label = placeholder.label,
            isFailed = isFailed,
            imageBoxStyle = placeholder.boxStyle,
            textStyle = textStyle,
            currentColor = placeholder.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor,
            usePublisherColors = publisherColorsEnabled,
        )
        placeholder.kind == ReaderBlockKind.SEPARATOR -> TeddDivider(modifier = Modifier.fillMaxSize())
        placeholder.kind == ReaderBlockKind.IMAGE || placeholder.kind == ReaderBlockKind.COVER_IMAGE -> EpubImageBox(
            imageBytes = imageBytes,
            imageCacheKey = imageCacheKey,
            label = placeholder.label,
            isFailed = isFailed,
            boxStyle = placeholder.boxStyle,
            currentColor = placeholder.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor,
            usePublisherColors = publisherColorsEnabled,
        )
        else -> Box(modifier = Modifier.fillMaxSize())
    }
}

/**
 * floated 이미지 placeholder를, 이미지는 시작/끝 가장자리에 두고 그 옆에 맞춰진 선두 단락 조각을 두는
 * row 형태 분할을 콘텐츠로 갖는 인라인 전체-열 박스로 배치한다.
 *
 * @param floatContent floated 블록에 맞춰진 텍스트와 이미지 geometry.
 * @param imageBytes 디코딩할 인코딩된 이미지 바이트, 추출이 진행 중이면 null.
 * @param imageCacheKey Coil이 디코딩된 이미지를 재사용하는 데 쓰는, 문서와 href로 결정되는 안정적인 식별자.
 * @param label 접근성과 실패 시 사용되는 대체 텍스트.
 * @param isFailed 추출이 이미 실패했는지 여부.
 * @param imageBoxStyle 이미지 쪽 절반을 감싸는 퍼블리셔 스타일링.
 * @param textStyle 맞춰진 텍스트 쪽 절반에 적용되는, 해석이 끝난 style.
 * @param currentColor 대체 테두리 색상.
 * @param usePublisherColors 퍼블리셔 색상을 존중할지 여부.
 */
@Composable
private fun EpubFloatPlaceholder(
    floatContent: ReaderFloatContent,
    imageBytes: ByteArray?,
    imageCacheKey: String?,
    label: String?,
    isFailed: Boolean,
    imageBoxStyle: ReaderBoxStyle?,
    textStyle: TextStyle,
    currentColor: Color,
    usePublisherColors: Boolean,
) {
    Layout(
        content = {
            EpubImageBox(
                imageBytes = imageBytes,
                imageCacheKey = imageCacheKey,
                label = label,
                isFailed = isFailed,
                boxStyle = imageBoxStyle,
                currentColor = currentColor,
                usePublisherColors = usePublisherColors,
            )
            BasicText(
                text = floatContent.text.annotatedString,
                style = textStyle,
                inlineContent = emptyMap(),
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val imageFraction = (floatContent.imageWidthEm / floatContent.columnWidthEm).coerceIn(0f, 1f)
        val imageWidthPx = (constraints.maxWidth * imageFraction).toInt().coerceIn(0, constraints.maxWidth)
        val imagePlaceable = measurables[0].measure(Constraints.fixed(width = imageWidthPx, height = constraints.maxHeight))
        val textPlaceable = measurables[1].measure(
            constraints.copy(minWidth = 0, maxWidth = (constraints.maxWidth - imagePlaceable.width).coerceAtLeast(0)),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (floatContent.side == ReaderFloat.START) {
                imagePlaceable.place(0, 0)
                textPlaceable.place(imagePlaceable.width, 0)
            } else {
                textPlaceable.place(0, 0)
                imagePlaceable.place(constraints.maxWidth - imagePlaceable.width, 0)
            }
        }
    }
}

/**
 * EPUB 이미지 한 장 — 전면 판(plate) 또는 인라인 그림 — 을 디코딩된 아트워크로, 혹은 이미지가 끝내 도착하지
 * 않으리라는 것이 확실해지면 그 자리에 텍스트 라벨로 그린다.
 *
 * 이미지 박스의 퍼블리셔 배경은 콘텐츠보다 먼저 그리고, 그다음 이미지 자체를 그리고, 그 위에 테두리를 그린다.
 * 이렇게 하면 측정된 박스 페이지 나누기를 정확히 유지하면서도 명시적인 이미지 프레임이 보이도록 유지된다.
 *
 * 그림은 두 단계로 도착한다 — 책에서 바이트를 읽어낸 뒤 디코딩한다 — 그리고 각 단계마다 무언가를 보여주면
 * 페이지가 자리 잡기까지 세 번 바뀌었다: alt 텍스트나 스피너, 그다음 두 번째 스피너, 그다음 그림. 공간이
 * 이미 올바른 크기로 예약되어 있으므로 [AsyncImage]는 로딩 painter를 전혀 그리지 않는다. 디코딩된 이미지만
 * ([ImageFadeMillis]에 걸쳐 크로스페이드되어, 그림이 끝날 때마다 툭 튀지 않고 페이지가 한 번에 자리 잡는다)
 * 표시되거나, 디코딩이 실제로 실패했을 때만 [label] 텍스트가 표시된다. 일반 composition을 사용하여 마운트된
 * 인라인 이미지마다 추가로 드는 subcomposition 비용을 피한다.
 *
 * @param imageBytes 이미지의 인코딩된 바이트. 사용할 수 있으면 그리고, null이면 [isFailed]도 true가 아닌 한
 * 아무것도 그리지 않으며, true라면 [label]을 표시한다.
 * @param imageCacheKey ByteArray 요청을 메모리 캐시 가능하게 만드는, 문서와 href로 결정되는 안정적인 식별자.
 * @param label 이미지가 없거나 디코딩에 실패했을 때 표시되는 alt 텍스트이며, 정상적으로 렌더링되는 동안에는
 * content description으로도 쓰인다.
 * @param isFailed 이 이미지가 이전에 디코딩에 실패한 적이 있는지 여부. 그렇다면 여기서 [imageBytes]가
 * 없어도 아무것도 그리지 않는 대신 [label]을 표시한다.
 * @param boxStyle 이미지 자체에 붙은 퍼블리셔 박스 스타일링으로, 배경·테두리·radius를 포함한다.
 * @param currentColor `currentColor` 방식 테두리에 쓰이는 대체 색상.
 * @param usePublisherColors 테마 기본값 대신 명시적인 퍼블리셔 색상을 존중할지 여부.
 * @param modifier 이미지 루트에 적용되는 modifier. 이미지가 차지해야 할 정확한 크기를 이미 담고 있어야 한다.
 */
@Composable
private fun EpubImageBox(
    imageBytes: ByteArray?,
    imageCacheKey: String?,
    label: String?,
    isFailed: Boolean,
    boxStyle: ReaderBoxStyle?,
    currentColor: Color,
    usePublisherColors: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = teddReaderColors()
    val typography = teddReaderTypography()
    val platformContext = LocalPlatformContext.current
    val request = remember(imageBytes, imageCacheKey, platformContext) {
        imageBytes?.let { bytes ->
            ImageRequest.Builder(platformContext)
                .data(bytes)
                .apply {
                    if (imageCacheKey != null) memoryCacheKey(imageCacheKey)
                }
                .maxBitmapSize(Size(MaxInlineImageDimensionPx, MaxInlineImageDimensionPx))
                .crossfade(ImageFadeMillis)
                .build()
        }
    }
    var decodeFailed by remember(request) { mutableStateOf(false) }
    val radiusPercent = boxStyle?.borderRadiusPercent?.toInt()?.coerceIn(0, 100)
    val decoratedModifier = modifier
        .fillMaxSize()
        .run { if (radiusPercent != null && radiusPercent > 0) clip(RoundedCornerShape(percent = radiusPercent)) else this }
        .drawWithContent {
            val rect = Rect(Offset.Zero, size)
            drawReaderBoxBackground(boxStyle, rect, usePublisherColors)
            drawContent()
            drawReaderBoxBorders(boxStyle, rect, currentColor, usePublisherColors)
        }

    Box(modifier = decoratedModifier, contentAlignment = Alignment.Center) {
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                onError = { decodeFailed = true },
            )
        }
        if (decodeFailed || request == null && isFailed) {
            TeddText(
                text = label ?: stringResource(Res.string.visual_page_unavailable),
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * 하나의 EPUB 안에 있는 이미지 한 장에 대한 안정적인 메모리 캐시 식별자를 만든다.
 *
 * Coil 3.5.0은 [ByteArray] 데이터를 가져올 수는 있지만 `Keyer<ByteArray>`가 없으므로, 중복된 page-effect
 * composition들이 디코딩된 비트맵을 공유할 수 있는 유일한 방법은 명시적인 키뿐이다. 문서 URI는 href 앞에
 * 길이 접두사가 붙어서, 서로 다른 책들 사이에 동일한 컨테이너 경로가 섞이지 않도록 격리한다. 식별자가
 * 하나라도 없으면 null을 반환하여 Coil이 실수로 부분적인 키로 이미지를 캐시하지 못하도록 한다.
 *
 * @param documentUri 이미지를 소유한 EPUB의 안정적인 소스 URI.
 * @param imageHref 그 EPUB 안에서 컨테이너 기준 상대 이미지 경로.
 * @return 문서 범위로 한정된 Coil 메모리 캐시 키. 둘 중 하나라도 식별자가 없으면 null.
 */
internal fun epubImageMemoryCacheKey(documentUri: String?, imageHref: String?): String? {
    val document = documentUri?.takeIf(String::isNotBlank) ?: return null
    val href = imageHref?.takeIf(String::isNotBlank) ?: return null
    return "epub:${document.length}:$document$href"
}

private fun DrawScope.drawContainerDecorations(
    layout: TextLayoutResult,
    decorations: List<ReaderContainerDecoration>,
    baseTextColor: Color,
    publisherColorsEnabled: Boolean,
    emPx: Float,
) {
    decorations
        .filterNot(ReaderContainerDecoration::isPageContainer)
        .forEach { decoration ->
            val rect = fullWidthRangeRect(layout, decoration.start, decoration.end)
                ?.grownByPadding(decoration, emPx)
                ?: return@forEach
            val currentColor = decoration.foregroundColor.takeIf { publisherColorsEnabled }?.toColor() ?: baseTextColor
            drawReaderBoxBackground(
                boxStyle = decoration.boxStyle,
                rect = rect,
                usePublisherColors = publisherColorsEnabled,
            )
            drawReaderBoxBorders(
                boxStyle = decoration.boxStyle,
                rect = rect,
                currentColor = currentColor,
                usePublisherColors = publisherColorsEnabled,
                drawTop = decoration.startsHere,
                drawBottom = decoration.endsHere,
            )
        }
}

private fun fullWidthRangeRect(layout: TextLayoutResult, start: Int, end: Int): Rect? {
    if (end <= start || layout.layoutInput.text.length == 0) return null
    val safeStart = start.coerceIn(0, layout.layoutInput.text.length - 1)
    val safeEnd = (end - 1).coerceIn(safeStart, layout.layoutInput.text.length - 1)
    val firstLine = layout.getLineForOffset(safeStart)
    val lastLine = layout.getLineForOffset(safeEnd)
    return Rect(
        left = 0f,
        top = layout.getLineTop(firstLine),
        right = layout.size.width.toFloat(),
        bottom = layout.getLineBottom(lastLine),
    )
}

private fun DrawScope.drawReaderBoxBackground(
    boxStyle: ReaderBoxStyle?,
    rect: Rect,
    usePublisherColors: Boolean,
) {
    boxStyle ?: return
    if (!usePublisherColors) return
    val radius = ((boxStyle.borderRadiusPercent ?: 0f).coerceIn(0f, 100f) / 100f) * minOf(rect.width, rect.height)
    boxStyle.backgroundColor?.let { color ->
        if (radius > 0f) {
            drawRoundRect(
                color = color.toColor(),
                topLeft = rect.topLeft,
                size = rect.size,
                cornerRadius = CornerRadius(radius, radius),
            )
        } else {
            drawRect(color.toColor(), rect.topLeft, rect.size)
        }
    }
}

private fun DrawScope.drawReaderBoxBorders(
    boxStyle: ReaderBoxStyle?,
    rect: Rect,
    currentColor: Color,
    usePublisherColors: Boolean,
    drawTop: Boolean = true,
    drawBottom: Boolean = true,
) {
    boxStyle ?: return
    val insetRect = rect.insetForBorders(boxStyle, density)
    if (drawTop) drawBorderSide(boxStyle.borderTop, insetRect.left, insetRect.top, insetRect.right, insetRect.top, currentColor, usePublisherColors)
    drawBorderSide(boxStyle.borderRight, insetRect.right, insetRect.top, insetRect.right, insetRect.bottom, currentColor, usePublisherColors)
    if (drawBottom) drawBorderSide(boxStyle.borderBottom, insetRect.left, insetRect.bottom, insetRect.right, insetRect.bottom, currentColor, usePublisherColors)
    drawBorderSide(boxStyle.borderLeft, insetRect.left, insetRect.top, insetRect.left, insetRect.bottom, currentColor, usePublisherColors)
}

private fun Rect.insetForBorders(boxStyle: ReaderBoxStyle, density: Float): Rect {
    val halfStroke = maxBorderHalfStrokePx(boxStyle, density)
    return if (halfStroke == 0f) this else Rect(
        left = left + halfStroke,
        top = top + halfStroke,
        right = right - halfStroke,
        bottom = bottom - halfStroke,
    )
}

internal fun maxBorderHalfStrokePx(boxStyle: ReaderBoxStyle, density: Float): Float =
    listOf(
        boxStyle.borderTop?.widthPx,
        boxStyle.borderRight?.widthPx,
        boxStyle.borderBottom?.widthPx,
        boxStyle.borderLeft?.widthPx,
    ).mapNotNull { it?.takeIf { width -> width > 0f } }
        .maxOrNull()
        ?.times(density)
        ?.times(0.5f)
        ?: 0f

/**
 * 텍스트 페인 전체에 칠할, 가장 깊은 page-container의 퍼블리셔 배경(있는 경우).
 *
 * html/body 같은 컨테이너는 레이아웃된 글리프 경계뿐 아니라 리더의 텍스트 페이지 전체를 덮으므로, 페인
 * 배경은 레이아웃별 장식 처리 단계와 별도로 선택된다.
 */
/**
 * 책이 자신의 `html`/`body`에 요구하는 페이지 여백(em 단위), 아무것도 요구하지 않으면
 * [ReaderPageMarginsEm.Zero].
 *
 * 리플로우 가능한 책은 `body`에 페이지 여백을 명시한다 — `body { margin: 2em }`은 EPUB 스타일시트에서 가장
 * 흔한 한 줄이다 — 그리고 이를 무시하는 리더는 책이 조판된 것보다 훨씬 넓은 열에서 텍스트를 가장자리까지
 * 채우게 된다. 여백은 page-container 블록(`html`과 `body`)에서 읽어 그중 가장 넓은 값이 이기며, 텍스트
 * 영역 바깥에 적용되어 페이지 나누기가 텍스트가 그려지는 것과 같은 열을 측정하도록 한다.
 *
 * 각 방향은 [MaxPageContainerMarginEm]으로 상한이 걸린다. 그렇지 않으면 읽는 페이지보다 더 넓은 여백을
 * 요구하는 책이 단어 하나 앉히기에도 너무 좁은 열을 남기게 된다.
 *
 * @param page 컨테이너 스타일링을 담은 블록을 가진 페이지.
 * @return 텍스트 영역을 안쪽으로 밀어 넣을 여백.
 */
internal fun epubPageContainerMarginsEm(page: ReaderPageUi): ReaderPageMarginsEm {
    val containers = page.blocks.filter(ReaderBlock::isPageContainer)
    if (containers.isEmpty()) return ReaderPageMarginsEm.Zero
    // margin과 padding은 방향별로 합산된다: `html`/`body`에서는 둘 다 그저 페이지 가장자리와 텍스트 사이에
    // 놓이는 것일 뿐이며, 파서는 page-container 간격을 문단별 inset에서 의도적으로 제외해 두었으므로
    // 정확히 한 번만 여기에 반영된다.
    fun widest(margin: (ReaderBlockStyle) -> Float?, padding: (ReaderBlockStyle) -> Float?): Float =
        containers.mapNotNull { block ->
            block.style?.let { style -> (margin(style) ?: 0f) + (padding(style) ?: 0f) }
        }
            .maxOrNull()
            ?.coerceIn(0f, MaxPageContainerMarginEm)
            ?: 0f
    return ReaderPageMarginsEm(
        start = widest(ReaderBlockStyle::marginStartEm, ReaderBlockStyle::paddingStartEm),
        top = widest(ReaderBlockStyle::marginTopEm, ReaderBlockStyle::paddingTopEm),
        end = widest(ReaderBlockStyle::marginEndEm, ReaderBlockStyle::paddingEndEm),
        bottom = widest(ReaderBlockStyle::marginBottomEm, ReaderBlockStyle::paddingBottomEm),
    )
}

/**
 * 책 자신의 `body` 규칙이 명시하는, em 단위 페이지 여백.
 *
 * @property start inline-start 여백(em).
 * @property top 텍스트 영역 위쪽 여백(em).
 * @property end inline-end 여백(em).
 * @property bottom 텍스트 영역 아래쪽 여백(em).
 */
data class ReaderPageMarginsEm(
    val start: Float,
    val top: Float,
    val end: Float,
    val bottom: Float,
) {
    /** 책이 페이지 여백을 전혀 요구하지 않아서 리더 자체의 padding만 단독으로 적용될 때 true. */
    fun isZero(): Boolean = this == Zero

    /** 아무것도 명시하지 않은 책에게 주어지는 여백인 [Zero]를 담는다. */
    companion object {
        /** 어느 방향으로도 페이지 여백이 없음. */
        val Zero = ReaderPageMarginsEm(0f, 0f, 0f, 0f)
    }
}

/** 텍스트 열이 더 이상 읽기 어려워지기 전까지, 이 리더가 방향별로 허용하는 가장 넓은 페이지 여백. */
private const val MaxPageContainerMarginEm = 4f

internal fun epubPageContainerBackgroundColor(
    page: ReaderPageUi,
    style: ReaderStyle,
): ReaderColor? {
    if (style.themeMode != ReaderThemeMode.PUBLISHER) return null
    return page.blocks
        .asSequence()
        .filter(ReaderBlock::isPageContainer)
        .mapNotNull { block ->
            block.style?.boxStyle?.backgroundColor
                ?.takeUnless { (it.argb ushr 24) == 0L }
                ?.let { color -> block.level to color }
        }
        .maxByOrNull { (level, _) -> level }
        ?.second
}

/**
 * 퍼블리셔 스타일링이 텍스트 색상을 제공하지 않을 때만 쓰이는 대체 텍스트 색상. 퍼블리셔 페이지 배경이 리더
 * 배경보다 우선하므로, 그 명도(luminance) 역시 대체 잉크 색을 결정해야 한다. 그렇지 않으면 다크 기기에서
 * 밝은 퍼블리셔 페이지가 다크 테마의 밝은 잉크색을 받아 읽을 수 없게 된다.
 */
internal fun epubPageBaseTextColor(page: ReaderPageUi, style: ReaderStyle): ReaderColor {
    val publisherBackground = epubPageContainerBackgroundColor(page, style) ?: return style.textColor
    return ReaderColor(
        if (publisherBackground.toColor().luminance() > 0.5f) ReaderLightTextArgb else ReaderDarkTextArgb,
    )
}

/** EPUB body style이되, 지정되지 않은 전경색 대체값은 실제로 칠해질 페이지에 맞춰져 있다. */
internal fun epubPageTextStyle(page: ReaderPageUi, style: ReaderStyle): TextStyle =
    style.readerTextStyle().copy(color = epubPageBaseTextColor(page, style).toColor())

/**
 * 이 rect를 박스 자체의 padding만큼 넓혀서, 테두리가 텍스트를 가로지르지 않고 텍스트 바깥에 그려지도록 한다.
 *
 * 텍스트 레이아웃은 줄이 어디 있는지만 알기 때문에, 거기서 측정한 박스는 감싼 글리프에서 정확히 끝난다 —
 * 그래서 `border-top`과 `padding: 1em 0`으로 어느 섹션을 감싼 책은 그 규칙 선이 해당 섹션의 첫 줄과 마지막
 * 줄을 가로질러 그려지곤 했다. 공간은 박스 양쪽의 간격에 예약되어 있고(렌더러의 container-edge 계산 참고),
 * 그 자리를 여기서 가져다 쓴다.
 *
 * @receiver 박스의 줄들이 차지하는 rect.
 * @param decoration 그려지는 박스로, padding을 em 단위로 명시한다.
 * @param emPx 1em이 몇 픽셀인지.
 * @return 배경과 테두리를 그릴 rect.
 */
private fun Rect.grownByPadding(decoration: ReaderContainerDecoration, emPx: Float): Rect = Rect(
    left = left,
    top = top - if (decoration.startsHere) decoration.paddingTopEm * emPx else 0f,
    right = right,
    bottom = bottom + if (decoration.endsHere) decoration.paddingBottomEm * emPx else 0f,
)

private fun DrawScope.drawBorderSide(
    border: ReaderBorder?,
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    fallbackColor: Color,
    usePublisherColors: Boolean,
) {
    val width = border?.widthPx?.takeIf { it > 0f }?.times(density) ?: return
    drawLine(
        color = if (usePublisherColors) border.color?.toColor() ?: fallbackColor else fallbackColor,
        start = Offset(startX, startY),
        end = Offset(endX, endY),
        strokeWidth = width,
    )
}

/** [EpubImageBox]가 그리는 모든 EPUB 이미지에 요청하는 디코딩 크기(픽셀). */
private const val MaxInlineImageDimensionPx = 2_048

/** 디코딩된 EPUB 이미지가 크로스페이드로 나타나는 데 걸리는 시간(밀리초). [EpubImageBox] 참고. */
private const val ImageFadeMillis = 120
