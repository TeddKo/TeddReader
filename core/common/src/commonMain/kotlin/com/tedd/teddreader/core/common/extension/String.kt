package com.tedd.teddreader.core.common.extension

/**
 * 모든 줄 끝을 `\n`으로 통일한 텍스트로, 문서에서 읽은 모든 내용을 저장하기 전에 적용한다.
 *
 * 그렇지 않으면 Windows와 구형 Mac의 줄 끝이 다른 요소들이 기준으로 삼는 문자 오프셋의 일부가 된다. CRLF는 문자 두 개로 계산되므로, 한 플랫폼에서 작성한 책을 다른 플랫폼에서 페이지로 나누면 저장된 독서 위치가 잘못된 줄을 가리키게 된다.
 *
 * @receiver 문서에서 읽은 원래 텍스트.
 * @return 모든 CRLF와 단독 CR을 하나의 `\n`으로 바꾼 같은 텍스트.
 */
fun String.normalizedLineBreaks(): String = replace("\r\n", "\n").replace('\r', '\n')

/**
 * 이 텍스트가 공백뿐이면 [placeholder]를 반환한다. 공백으로만 된 제목을 문서가 제공해도 화면에는 무언가 표시해야 하기 때문이다. 공백 문자열은 값이 없는 것과 다르므로 `?:`만으로는 걸러지지 않는다.
 *
 * @receiver 검사할 텍스트로, 값이 없는 대신 공백뿐일 수 있다.
 * @param placeholder 대신 표시할 내용.
 * @return 이 텍스트 또는 공백 문자만 포함한 경우 [placeholder].
 */
fun String.ifBlankPlaceholder(placeholder: String): String = ifBlank { placeholder }
