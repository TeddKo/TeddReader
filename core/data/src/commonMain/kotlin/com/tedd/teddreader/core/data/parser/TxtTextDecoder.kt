package com.tedd.teddreader.core.data.parser

/**
 * `.txt` 파일이 스스로 선언한 인코딩을 갖지 않을 때 그 원시 바이트를 텍스트로 디코딩한다 —
 * 순수 텍스트 책에서는 보통 그렇다. 이 리더가 여는 파일 대부분은 UTF-8이거나, UTF-8 채택
 * 이전의 오래된 한국어 텍스트 파일이라면 레거시 한국어 코드 페이지(Windows-949/CP949, EUC-KR의
 * 상위 집합)나 순수 UTF-16이므로, [decode]는 UTF-8이라고 가정해버려서 그것이 아닐 때 책 전체가
 * 대체 문자로 렌더링되게 두는 대신, 그 가능성 순서대로 인코딩들을 시도한다.
 */
object TxtTextDecoder {
    /**
     * [bytes]를 텍스트로 디코딩한다, 확실성이 높은 순서대로 인코딩을 시도하며 실제로 텍스트처럼
     * 보이는 결과를 취한다.
     *
     * 순서는 다음과 같다: 바이트 순서 표시(BOM)가 있으면 그것이 곧바로 문제를 해결한다
     * ([decodeBom]). 아니라면, 이미 유효한 UTF-8인 바이트는 곧바로 UTF-8로 디코딩된다 — 아무것도
     * 추측하기 전에 취하는 빠르고 확실한 경로다. 둘 다 아니면, 세 후보가 병렬로 디코딩된다 —
     * 레거시 한국어(플랫폼별 [decodeLegacyKoreanText]를 통해), UTF-16 리틀엔디안, UTF-16
     * 빅엔디안 — 그리고 [readableScore]로 점수가 매겨진다; 양수 점수 중 가장 높은 후보가
     * 승리한다. 여기서 잘못 고르는 것은 사소한 문제가 아니다: 한국어 소설이 읽을 수 있는
     * 산문으로 열리는 것과, 책 전체가 대체 문자 벽이나 깨진 글자로 열리는 것의 차이인데, 이
     * 함수가 반환하고 나면 그 뒤로 아무도 다시 추측하지 않기 때문이다.
     *
     * @param bytes 파일의 원시 내용.
     * @return 디코딩된 텍스트. 모든 후보가 0점 이하면(어느 것도 읽을 수 있어 보이지 않으면),
     *   손실 있는 UTF-8 디코딩으로 대체되는데, 이는 던지는 대신 디코딩할 수 없는 바이트
     *   시퀀스를 `U+FFFD`로 치환한다 — 이 함수는 절대 던지지 않는다.
     */
    fun decode(bytes: ByteArray): String {
        decodeBom(bytes)?.let { return it }
        if (bytes.isValidUtf8()) return bytes.decodeToString()

        return listOfNotNull(
            decodeLegacyKoreanText(bytes)?.takeIfReadable(),
            bytes.decodeUtf16LittleEndian(startIndex = 0).takeIfReadable(),
            bytes.decodeUtf16BigEndian(startIndex = 0).takeIfReadable(),
        ).maxByOrNull { it.readableScore() } ?: bytes.decodeToString()
    }

    /**
     * 앞쪽 바이트 순서 표시가 선언하는 인코딩으로 [bytes]를 디코딩하고, 그 표시 자체는 결과에서
     * 제거한다.
     *
     * @param bytes 파일의 원시 내용.
     * @return 디코딩된 텍스트, 또는 [bytes]가 UTF-8, UTF-16LE, UTF-16BE 바이트 순서 표시 중
     *   어느 것으로도 시작하지 않으면 `null` — 인코딩이 선언되지 않았으므로 대신 추측해야
     *   한다는 뜻이다.
     */
    private fun decodeBom(bytes: ByteArray): String? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> bytes.copyOfRange(3, bytes.size).decodeToString()
        bytes.startsWith(0xFF, 0xFE) -> bytes.decodeUtf16LittleEndian(startIndex = 2)
        bytes.startsWith(0xFE, 0xFF) -> bytes.decodeUtf16BigEndian(startIndex = 2)
        else -> null
    }
}

/**
 * [bytes]를 레거시 한국어 텍스트(Windows-949/CP949 코드 페이지 계열)로 디코딩한다. UTF-8 이전
 * 한국어 `.txt` 파일 대부분이 이 인코딩으로 저장되었다. JVM도 Kotlin/Native도 공통 코드에서
 * 바로 쓸 수 있는 것을 제공하지 않으므로, 각 플랫폼은 공유되는 Kotlin 구현이 아니라 자신이
 * 가진 네이티브 API를 통해 이 코드 페이지에 도달한다.
 *
 * @param bytes 파일의 원시 내용.
 * @return 디코딩된 텍스트, 또는 플랫폼의 디코더가 이 인코딩으로 [bytes]를 매핑할 수 없으면
 *   `null` — 절대 던지지 않는다; 디코딩 실패는 예외가 아니라 `null`로 보고되므로, [decode]는
 *   그것을 그저 실패한 다른 후보 하나로 취급할 수 있다.
 */
internal expect fun decodeLegacyKoreanText(bytes: ByteArray): String?

/**
 * 실제 텍스트처럼 보이지 않는 디코딩 후보를, [readableScore]만으로 다른 것들과 경쟁하기 전에
 * 걸러낸다. 대체 문자나 제어 바이트가 지배적인 후보가 똑같이 잘못된 다른 후보보다 "덜
 * 부정적인" 점수를 가졌다는 이유만으로 이기는 일이 없도록 한다.
 *
 * @receiver 같은 원본 바이트의 후보 디코딩.
 * @return 이 문자열, 또는 [readableScore]가 0 이하면 `null`.
 */
private fun String.takeIfReadable(): String? = takeIf { it.readableScore() > 0 }

/**
 * 이 문자열이 잘못된 인코딩으로 바이트를 디코딩한 결과물이 아니라 실제로 읽을 수 있는 텍스트에
 * 얼마나 가까운지에 대한 휴리스틱 점수. [decode]는 여러 추측 중 가장 높은 점수를 받은 후보를
 * 고르므로, 이 점수가 실제로 책 전체가 어느 인코딩으로 보일지를 결정한다.
 *
 * 문자별 가중치: `U+FFFD`(유니코드 대체 문자)나 NUL 바이트는 -100점인데, 둘 다 코덱이 그
 * 바이트를 전혀 매핑하지 못했다는 뜻이라 — 인코딩이 잘못되었다는 강한 신호다. 개행, 캐리지
 * 리턴, 탭은 +1점인데, 실제 산문에는 줄바꿈이 있기 때문이다. 한글 음절(`U+AC00`..`U+D7A3`)은
 * +5점, 가장 강한 양의 신호인데, *올바른* 레거시 코드 페이지로 디코딩된 한국어 텍스트는 이런
 * 문자를 아주 많이 만들어내는 반면 잘못된 추측은 전혀 만들어내지 못하거나 쓰레기 속에
 * 뒤죽박죽 흩뿌린다. 출력 가능한 ASCII(공백부터 `~`까지)는 +2점. 그 외 ISO 제어 문자는
 * -20점인데, 실제 텍스트에는 사실상 없고 그 존재는 보통 멀티바이트 시퀀스가 잘못된 위치에서
 * 잘렸다는 뜻이기 때문이다. 나머지는 모두 0점.
 *
 * @receiver 같은 원본 바이트의 후보 디코딩.
 * @return 문자별 점수의 합; 높을수록 올바른 디코딩일 가능성이 크다.
 */
private fun String.readableScore(): Int = sumOf { char ->
    when {
        char == '\uFFFD' || char == '\u0000' -> -100
        char == '\n' || char == '\r' || char == '\t' -> 1
        char in '\uAC00'..'\uD7A3' -> 5
        char in ' '..'~' -> 2
        char.isISOControl() -> -20
        else -> 0
    }
}

/**
 * 이 바이트 배열이 [prefix]로 시작하는지 여부. 각 바이트를 부호 없는 값으로 비교하는데,
 * 그렇지 않으면 원시 부호 있는 [Byte]를 `0xFF` 같은 값과 비교했을 때 절대 일치하지 않기
 * 때문이다.
 *
 * @receiver 확인할 바이트들.
 * @param prefix 이 배열 시작 부분과 맞춰볼 부호 없는 바이트 값들(0-255).
 * @return 이 배열이 [prefix]보다 길거나 같고 바이트 단위로 일치하면 `true`.
 */
private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index].toInt() and 0xFF == prefix[index] }

/**
 * 이 바이트 배열을 리틀엔디안 바이트 순서의 UTF-16으로 디코딩한다, 2바이트 쌍마다 `Char` 하나씩.
 *
 * @receiver 디코딩할 바이트들.
 * @param startIndex 디코딩을 시작할 오프셋. 배열을 먼저 복사하지 않고도 앞쪽 바이트 순서 표시를
 *   건너뛸 수 있게 한다.
 * @return 디코딩된 텍스트. 짝을 이룰 바이트가 없는 끝의 홑 바이트는 오류를 일으키는 대신 조용히
 *   버려지는데, 이 함수는 UTF-16 서로게이트 쌍 검증을 전혀 하지 않기 때문이다.
 */
private fun ByteArray.decodeUtf16LittleEndian(startIndex: Int): String = buildString {
    var index = startIndex
    while (index + 1 < size) {
        append(
            ((this@decodeUtf16LittleEndian[index].toInt() and 0xFF) or
                ((this@decodeUtf16LittleEndian[index + 1].toInt() and 0xFF) shl 8)).toChar(),
        )
        index += 2
    }
}

/**
 * 이 바이트 배열을 빅엔디안 바이트 순서의 UTF-16으로 디코딩한다, 2바이트 쌍마다 `Char` 하나씩.
 *
 * @receiver 디코딩할 바이트들.
 * @param startIndex 디코딩을 시작할 오프셋. 배열을 먼저 복사하지 않고도 앞쪽 바이트 순서 표시를
 *   건너뛸 수 있게 한다.
 * @return 디코딩된 텍스트. 짝을 이룰 바이트가 없는 끝의 홑 바이트는 오류를 일으키는 대신 조용히
 *   버려지는데, 이 함수는 UTF-16 서로게이트 쌍 검증을 전혀 하지 않기 때문이다.
 */
private fun ByteArray.decodeUtf16BigEndian(startIndex: Int): String = buildString {
    var index = startIndex
    while (index + 1 < size) {
        append(
            (((this@decodeUtf16BigEndian[index].toInt() and 0xFF) shl 8) or
                (this@decodeUtf16BigEndian[index + 1].toInt() and 0xFF)).toChar(),
        )
        index += 2
    }
}

/**
 * 이 바이트 배열이 처음부터 끝까지 유효한 UTF-8인지 여부. 디코딩한 뒤 대체 문자를 찾는 대신
 * 인코딩 규칙에 직접 대조해서 확인한다 — [decode]는 이를 이용해 더 느린 다중 후보 추측 체인을
 * 전혀 돌리지 않고 빠르고 확실한 UTF-8 경로를 취한다.
 *
 * @receiver 검증할 바이트들.
 * @return 모든 앞쪽 바이트가 유효한 시퀀스 길이를 선언하고(유효하지 않은 `0x80..0xC1`,
 *   `0xF5..0xFF` 앞쪽 바이트 범위는 거부됨) 그것이 암시하는 모든 이어지는 바이트가
 *   `0x80..0xBF`에 속하며, 배열 끝에서 잘린 시퀀스가 없으면 `true`.
 */
private fun ByteArray.isValidUtf8(): Boolean {
    var index = 0
    while (index < size) {
        val first = this[index].toInt() and 0xFF
        val needed = when {
            first <= 0x7F -> 0
            first in 0xC2..0xDF -> 1
            first in 0xE0..0xEF -> 2
            first in 0xF0..0xF4 -> 3
            else -> return false
        }
        if (index + needed >= size) return false
        repeat(needed) { offset ->
            val next = this[index + offset + 1].toInt() and 0xFF
            if (next !in 0x80..0xBF) return false
        }
        index += needed + 1
    }
    return true
}
