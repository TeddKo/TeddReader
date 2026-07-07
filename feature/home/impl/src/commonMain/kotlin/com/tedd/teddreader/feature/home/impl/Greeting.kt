package com.tedd.teddreader.feature.home.impl

import com.tedd.teddreader.core.common.sayHello

class Greeting {
    private val platform = getPlatform()

    fun greet(): String = sayHello(platform.name)
}
