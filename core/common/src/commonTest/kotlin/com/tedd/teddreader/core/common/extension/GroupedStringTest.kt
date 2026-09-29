package com.tedd.teddreader.core.common.extension

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [toGroupedString]이 `#,##0` 패턴대로 세 자리마다 쉼표를 넣는지, 자릿수 경계와 음수·극값에서도 같은지 검증한다.
 */
class GroupedStringTest {
    /** 세 자리 이하 숫자에는 쉼표가 붙지 않아야 한다. */
    @Test
    fun smallNumbersHaveNoSeparator() {
        assertEquals("0", 0.toGroupedString())
        assertEquals("7", 7.toGroupedString())
        assertEquals("999", 999.toGroupedString())
    }

    /** 네 자리부터 세 자리 단위로 쉼표가 들어가야 한다. */
    @Test
    fun groupsEveryThreeDigits() {
        assertEquals("1,000", 1000.toGroupedString())
        assertEquals("12,345", 12345.toGroupedString())
        assertEquals("123,456", 123456.toGroupedString())
        assertEquals("1,234,567", 1234567.toGroupedString())
    }

    /** 음수와 타입 극값에서도 부호를 앞에 둔 채 같은 규칙을 따라야 한다. */
    @Test
    fun negativeAndExtremeValues() {
        assertEquals("-1,234", (-1234).toGroupedString())
        assertEquals("-999", (-999).toGroupedString())
        assertEquals("2,147,483,647", Int.MAX_VALUE.toGroupedString())
        assertEquals("-2,147,483,648", Int.MIN_VALUE.toGroupedString())
        assertEquals("-9,223,372,036,854,775,808", Long.MIN_VALUE.toGroupedString())
    }
}
