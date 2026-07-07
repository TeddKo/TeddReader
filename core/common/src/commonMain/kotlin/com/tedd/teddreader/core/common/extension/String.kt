package com.tedd.teddreader.core.common.extension

fun String.normalizedLineBreaks(): String = replace("\r\n", "\n").replace('\r', '\n')

fun String.ifBlankPlaceholder(placeholder: String): String = ifBlank { placeholder }
