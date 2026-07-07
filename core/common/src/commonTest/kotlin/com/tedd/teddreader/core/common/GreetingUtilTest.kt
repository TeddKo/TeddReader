package com.tedd.teddreader.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingUtilTest {
    @Test
    fun saysHello() {
        assertEquals("Hello, Test!", sayHello("Test"))
    }
}
