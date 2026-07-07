package com.tedd.teddreader.feature.home.impl

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
