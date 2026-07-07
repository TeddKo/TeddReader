package com.tedd.teddreader.app.reader.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidExternalDocumentIntentTest {
    @Test
    fun viewActionReturnsDataUri() {
        assertEquals(
            "content://docs/book.pdf",
            externalDocumentUriString(
                action = "android.intent.action.VIEW",
                dataUri = "content://docs/book.pdf",
                streamUri = null,
            ),
        )
    }

    @Test
    fun sendActionReturnsStreamUriOnly() {
        assertEquals(
            "content://docs/book.txt",
            externalDocumentUriString(
                action = "android.intent.action.SEND",
                dataUri = null,
                streamUri = "content://docs/book.txt",
            ),
        )
    }

    @Test
    fun sendActionWithoutStreamIsIgnored() {
        assertNull(
            externalDocumentUriString(
                action = "android.intent.action.SEND",
                dataUri = null,
                streamUri = null,
            ),
        )
    }
}
