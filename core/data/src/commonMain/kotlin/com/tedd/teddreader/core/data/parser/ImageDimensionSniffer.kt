package com.tedd.teddreader.core.data.parser

/**
 * 실제 이미지 디코더가 하듯이, 종횡비를 추측하는 대신 이미지 자체의 바이너리 헤더에서 곧바로 진짜
 * 픽셀 너비/높이를 읽는다. EPUB 패키지가 실제로 담아 배포하는 포맷을 지원한다: PNG, JPEG, GIF,
 * WebP, BMP, SVG. 그 외의 것이거나 잘리거나 손상된 파일이면 null을 반환한다.
 */
internal fun sniffImageDimensions(bytes: ByteArray): Pair<Int, Int>? =
    sniffPng(bytes) ?: sniffGif(bytes) ?: sniffWebp(bytes) ?: sniffBmp(bytes) ?: sniffJpeg(bytes)
        ?: sniffSvg(bytes)

/**
 * BMP의 너비/높이. 2바이트 `BM` 시그니처 다음에 오는 40바이트 BITMAPINFOHEADER에서 읽는다.
 *
 * 헤더의 높이 필드는 부호가 있다: 음수 값은 크기가 음수라는 뜻이 아니라, 이미지의 행이 보통의
 * 아래에서 위 방향이 아니라 위에서 아래 방향으로 저장되어 있다는 뜻일 뿐이다. 그래서 여기서는 부호를
 * 버리고 그 절대값을 실제 높이로 사용한다.
 */
private fun sniffBmp(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 26 || bytes[0] != 0x42.toByte() || bytes[1] != 0x4D.toByte()) return null
    val width = bytes.readInt32LE(18)
    val height = bytes.readInt32LE(22)
    return dimensionsOrNull(width, if (height < 0) -height else height)
}

/**
 * SVG의 크기. EPUB은 다른 어떤 래스터 포맷보다 훨씬 자주 삽화와 표지에 이를 쓴다 — 삽화를 이런 식으로
 * 감싼 책은 측정 가능한 그림 자체가 전혀 없었고, 그래서 그 하나하나가 페이지 전체를 요구하게 됐다.
 * `viewBox`를 먼저 읽는 이유는 그것이 실제로 비율을 결정하는 값이기 때문이다; 퍼센트 너비는 비율에
 * 대해 아무것도 말해주지 않으므로 건너뛴다.
 */
private fun sniffSvg(bytes: ByteArray): Pair<Int, Int>? {
    val header = bytes.decodeToString(endIndex = bytes.size.coerceAtMost(SvgHeaderChars))
    val openTag = SvgOpenTagRegex.find(header)?.value ?: return null

    SvgViewBoxRegex.find(openTag)?.groupValues?.get(1)?.let { viewBox ->
        val numbers = viewBox.trim().split(SvgSeparatorRegex).mapNotNull(String::toFloatOrNull)
        if (numbers.size >= 4 && numbers[2] > 0f && numbers[3] > 0f) {
            return roundedDimensions(numbers[2], numbers[3])
        }
    }

    val width = SvgWidthRegex.find(openTag)?.groupValues?.get(1)?.toSvgLength()
    val height = SvgHeightRegex.find(openTag)?.groupValues?.get(1)?.toSvgLength()
    if (width != null && height != null) return roundedDimensions(width, height)
    return null
}

/** 여전히 고정 크기로 해석되는 단위를 가진 SVG 길이; 퍼센트는 그렇지 않다. */
private fun String.toSvgLength(): Float? {
    val value = trim().removeSuffix("px").trim()
    if (value.isEmpty() || value.endsWith("%")) return null
    return value.toFloatOrNull()?.takeIf { it > 0f }
}

/**
 * [width]/[height]를 가장 가까운 정수 픽셀로 반올림하고 1로 바닥을 정한 값이다. 아주 얇은 선언
 * 크기 — 가느다란 줄 하나짜리 SVG는 정당하게 `viewBox="0 0 640 0.5"`라고 쓸 수 있다 — 가 0 크기
 * 이미지로 반올림되어 [dimensionsOrNull]이 측정 불가로 거부하는 일이 없도록 한다.
 */
private fun roundedDimensions(width: Float, height: Float): Pair<Int, Int>? = dimensionsOrNull(
    (width + 0.5f).toInt().coerceAtLeast(1),
    (height + 0.5f).toInt().coerceAtLeast(1),
)

/**
 * [sniffSvg]가 여는 `<svg>` 태그를 찾기 위해 스캔하는 파일 시작 부분의 크기 — 파일의 나머지를
 * 디코딩(심지어 다 읽는 것)하지 않고도 크기를 재기에 충분할 만큼, 현실적인 네임스페이스 선언들과
 * `viewBox`/`width`/`height`를 넉넉히 담을 수 있는 크기다.
 */
private const val SvgHeaderChars = 4096

/** 스캔된 헤더 안에서 SVG 루트 요소의 여는 태그를, 속성까지 포함해 매칭한다. */
private val SvgOpenTagRegex = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * `viewBox` 속성의 원시 값을 캡처한다 — 먼저 확인하는 이유는 그것이 실제로 SVG의 비율을 결정하는
 * 값이기 때문이다.
 */
private val SvgViewBoxRegex = Regex("""viewBox\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** `width` 속성의 원시 값을 캡처한다. 쓸 수 있는 `viewBox`를 찾지 못했을 때만 참조된다. */
private val SvgWidthRegex = Regex("""\bwidth\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** `height` 속성의 원시 값을 캡처한다. 쓸 수 있는 `viewBox`를 찾지 못했을 때만 참조된다. */
private val SvgHeightRegex = Regex("""\bheight\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

/** `viewBox` 값이 네 숫자를 구분하는 데 쓰는 쉼표나 공백 어느 쪽이든 기준으로 나눈다. */
private val SvgSeparatorRegex = Regex("""[\s,]+""")

/**
 * PNG의 너비/높이. 필수 IHDR 청크 자체의 빅엔디언 필드에서 읽는다.
 *
 * PNG는 IHDR이 8바이트 시그니처 바로 뒤, 반드시 첫 번째 청크여야 하도록 요구하므로, 청크 목록을
 * 전혀 순회하지 않고도 고정 오프셋에서 너비와 높이를 읽을 수 있다.
 */
private fun sniffPng(bytes: ByteArray): Pair<Int, Int>? {
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    if (bytes.size < 24 || !bytes.regionMatches(0, signature)) return null
    if (!bytes.regionMatches(12, "IHDR".encodeToByteArray())) return null
    val width = bytes.readInt32BE(16)
    val height = bytes.readInt32BE(20)
    return dimensionsOrNull(width, height)
}

/**
 * GIF의 너비/높이. `GIF87a`/`GIF89a` 시그니처 다음에 오는 고정 오프셋의 logical screen descriptor에서
 * 리틀엔디언으로 읽는다.
 */
private fun sniffGif(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 10) return null
    val isGif = bytes.regionMatches(0, "GIF87a".encodeToByteArray()) ||
        bytes.regionMatches(0, "GIF89a".encodeToByteArray())
    if (!isGif) return null
    val width = bytes.readInt16LE(6)
    val height = bytes.readInt16LE(8)
    return dimensionsOrNull(width, height)
}

/**
 * WebP의 너비/높이. 파일이 실제로 사용하는 세 가지 청크 포맷 중 어느 것이든 그로부터 읽는다.
 *
 * `VP8X`(확장 포맷)는 너비-1과 높이-1을 각각 별도의 리틀엔디언 24비트 필드로 저장한다. `VP8L`
 * (무손실)은 두 크기를 각각 1씩 뺀 값으로, 고정 `0x2F` 마커 바이트 바로 다음부터 시작하는 하나의
 * 리틀엔디언 32비트 값에 패킹한다. 일반 `VP8 `(손실)는 3바이트 프레임 태그 뒤에 코덱의
 * `0x9d 0x01 0x2a` 동기화 코드가 오며, 그 동기화 코드를 지난 뒤에야 각각 하위 14비트로 마스킹된
 * 리틀엔디언 너비/높이 필드가 나타난다.
 */
private fun sniffWebp(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 30) return null
    if (!bytes.regionMatches(0, "RIFF".encodeToByteArray())) return null
    if (!bytes.regionMatches(8, "WEBP".encodeToByteArray())) return null
    return when {
        bytes.regionMatches(12, "VP8X".encodeToByteArray()) -> {
            val width = bytes.readInt24LE(24) + 1
            val height = bytes.readInt24LE(27) + 1
            dimensionsOrNull(width, height)
        }
        bytes.regionMatches(12, "VP8L".encodeToByteArray()) -> {
            if (bytes.size < 25 || bytes[20] != 0x2F.toByte()) return null
            val bits = bytes.readInt32LE(21)
            val width = (bits and 0x3FFF) + 1
            val height = ((bits shr 14) and 0x3FFF) + 1
            dimensionsOrNull(width, height)
        }
        bytes.regionMatches(12, "VP8 ".encodeToByteArray()) -> {
            if (bytes.size < 30) return null
            if (bytes[23] != 0x9d.toByte() || bytes[24] != 0x01.toByte() || bytes[25] != 0x2a.toByte()) return null
            val width = bytes.readInt16LE(26) and 0x3FFF
            val height = bytes.readInt16LE(28) and 0x3FFF
            dimensionsOrNull(width, height)
        }
        else -> null
    }
}

/**
 * JPEG의 너비/높이. SOI(`FF D8`) 뒤의 마커 세그먼트들을 start-of-frame 마커가 나올 때까지 걸으며
 * 찾는다.
 *
 * PNG/GIF/WebP와 달리 JPEG은 크기를 고정 오프셋에 두지 않는다 — 임베디드 EXIF 썸네일이나 다른
 * `APPn` 세그먼트가 그 앞에 올 수 있다 — 그래서 세그먼트를 하나씩 걸어야 한다. 마커 사이의 채움
 * 용도로만 쓰인 `0xFF` 바이트는 건너뛰고(`marker == 0xFF` 분기), 페이로드 세그먼트가 없는 마커들 —
 * SOI/EOI와 `RSTn` 재시작 마커 `0xD0`..`0xD7` — 도 길이를 읽지 않고 건너뛴다. 세그먼트 길이를 일단
 * 읽고 나면, SOF 세그먼트의 1바이트 샘플 정밀도 바로 다음에 높이, 너비가 온다. 마커 `0xC4`,
 * `0xC8`, `0xCC`(DHT, 예약된 JPG 확장, DAC)는 SOF 마커도 차지하는 `0xC0`..`0xCF` 범위 안에 들지만
 * SOF는 아니므로, 프레임 헤더로 오독되지 않도록 명시적으로 제외된다. 어떤 SOF도 찾지 못한 채
 * SOS(`0xFFDA`, 엔트로피 코딩된 스캔 데이터가 시작되는 지점)에 도달하면 이 파일에는 보고할 것이
 * 전혀 없다는 뜻이므로, 마커로 이루어져 있지 않은 압축 데이터로 계속 진행하는 대신 여기서 null을
 * 반환한다.
 */
private fun sniffJpeg(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
    var index = 2
    while (index + 3 < bytes.size) {
        if (bytes[index] != 0xFF.toByte()) {
            index += 1
            continue
        }
        val marker = bytes[index + 1].toInt() and 0xFF
        if (marker == 0xFF) {
            index += 1
            continue
        }
        if (marker == 0xD8 || marker == 0xD9 || (marker in 0xD0..0xD7)) {
            index += 2
            continue
        }
        val segmentLength = bytes.readInt16BE(index + 2)
        if (segmentLength < 2) return null
        val isSofMarker = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isSofMarker) {
            val precisionOffset = index + 4
            if (precisionOffset + 4 >= bytes.size) return null
            val height = bytes.readInt16BE(precisionOffset + 1)
            val width = bytes.readInt16BE(precisionOffset + 3)
            return dimensionsOrNull(width, height)
        }
        if (marker == 0xDA) return null
        index += 2 + segmentLength
    }
    return null
}

/**
 * [width]와 [height]를 결과로 묶은 것, 혹은 둘 중 하나라도 실제 양수 크기가 아니면 null.
 *
 * 위의 포맷별 스니퍼 모두가 거쳐가는 공유 가드다: 0 또는 음수 크기로 디코딩되는 헤더는 손상되었거나
 * 지원되지 않는 것이며, 시그니처가 아예 매칭되지 않은 것과 같은 방식으로 취급된다. 호출자에게
 * 가짜 크기를 넘기는 대신 그렇게 처리한다.
 */
private fun dimensionsOrNull(width: Int, height: Int): Pair<Int, Int>? =
    if (width > 0 && height > 0) width to height else null

/**
 * [this]의 [offset] 위치부터 [other]가 바이트 단위로 정확히 나타나는지 여부. 비교만을 위한 서브
 * 배열을 할당하지 않는다.
 *
 * @receiver 탐색 대상 버퍼.
 * @param offset 수신자 안에서 [other]가 시작해야 하는 위치; 음수 오프셋이나 [other]를 담기에 너무
 *   짧은 수신자는 예외를 던지는 대신 매칭 실패로 처리된다.
 * @param other [offset]에 나타나야 하는 바이트들.
 */
private fun ByteArray.regionMatches(offset: Int, other: ByteArray): Boolean {
    if (offset < 0 || offset + other.size > size) return false
    for (i in other.indices) if (this[offset + i] != other[i]) return false
    return true
}

/**
 * [offset]의 두 바이트를 빅엔디언 부호 없는 16비트 정수로 읽는다 — JPEG이 세그먼트 길이와 SOF
 * 너비/높이 필드를 저장하는 방식이다.
 *
 * @receiver 읽을 버퍼.
 * @param offset 두 바이트 중 첫 번째의 인덱스.
 */
private fun ByteArray.readInt16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

/**
 * [offset]의 두 바이트를 리틀엔디언 부호 없는 16비트 정수로 읽는다 — GIF의 logical screen
 * descriptor와 `VP8 ` WebP 청크가 자신의 크기를 저장하는 방식이다.
 *
 * @receiver 읽을 버퍼.
 * @param offset 두 바이트 중 첫 번째의 인덱스.
 */
private fun ByteArray.readInt16LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/**
 * [offset]의 세 바이트를 리틀엔디언 부호 없는 24비트 정수로 읽는다 — `VP8X` WebP 청크가 너비-1과
 * 높이-1 필드 각각을 저장하는 방식이다.
 *
 * @receiver 읽을 버퍼.
 * @param offset 세 바이트 중 첫 번째의 인덱스.
 */
private fun ByteArray.readInt24LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

/**
 * [offset]의 네 바이트를 빅엔디언 32비트 정수로 읽는다 — PNG의 IHDR 청크가 너비와 높이를 저장하는
 * 방식이다.
 *
 * @receiver 읽을 버퍼.
 * @param offset 네 바이트 중 첫 번째의 인덱스.
 */
private fun ByteArray.readInt32BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/**
 * [offset]의 네 바이트를 리틀엔디언 32비트 정수로 읽는다 — BMP의 BITMAPINFOHEADER가 너비와(음수일
 * 수도 있는) 높이를 저장하는 방식이고, `VP8L` WebP 청크가 두 크기를 하나의 필드에 패킹하는 방식이다.
 *
 * @receiver 읽을 버퍼.
 * @param offset 네 바이트 중 첫 번째의 인덱스.
 */
private fun ByteArray.readInt32LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
