package com.tedd.teddreader.core.common.extension

/**
 * Text with every line ending as `\n`, applied to anything read out of a document before it is stored.
 *
 * Windows and classic Mac line endings would otherwise become part of the character offsets everything
 * else keys on: a CRLF counts as two characters, so a book written on one platform and paginated on
 * another would put a stored reading position on the wrong line.
 *
 * @receiver text as it was read out of a document.
 * @return the same text with every CRLF and lone CR turned into a single `\n`.
 */
fun String.normalizedLineBreaks(): String = replace("\r\n", "\n").replace('\r', '\n')

/**
 * [placeholder] when this text is blank, for a screen that must show something where a document supplied
 * a title made only of spaces — blank is not the same as absent, and `?:` alone would let it through.
 *
 * @receiver the text to test, which may be blank rather than absent.
 * @param placeholder what to show instead.
 * @return this text, or [placeholder] when it holds nothing but whitespace.
 */
fun String.ifBlankPlaceholder(placeholder: String): String = ifBlank { placeholder }
