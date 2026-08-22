package com.tedd.teddreader.core.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.buffer
import okio.openZip

class EpubImportContainerTest {
    @Test
    fun parseEpubSpineItemReusesLinkedStyleResultsAcrossProgressiveSections() {
        val fileSystem = FileSystem.SYSTEM
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "epub-import-container-test.epub"
        fileSystem.sink(path).buffer().use { sink ->
            sink.write(sampleProgressiveCssReuseEpubBytes())
        }
        try {
            val zip = fileSystem.openZip(path)
            val container = openEpubImportContainer(zip, title = "CSS Reuse")
            assertNotNull(container)

            parseEpubSpineItem(container, spinePosition = 0, sectionIndex = 0, baseOffset = 0L)
            parseEpubSpineItem(container, spinePosition = 1, sectionIndex = 1, baseOffset = 100L)

            assertEquals(setOf("OEBPS/styles/shared.css"), container.linkedStyleSheetCache.keys)
            assertEquals(setOf("OEBPS/styles/shared.css"), container.linkedCssCache.keys)
            assertEquals(1, container.linkedStyleSheetCache.size)
            assertEquals(1, container.linkedCssCache.size)
            assertTrue(container.linkedStyleSheetCache.values.first().widthFor(listOf("lead")) != null)
            assertTrue(container.linkedCssCache.values.first() != EpubCss.Empty)
        } finally {
            runCatching { fileSystem.delete(path) }
        }
    }
}

private fun sampleProgressiveCssReuseEpubBytes(): ByteArray =
    java.io.ByteArrayOutputStream().use { output ->
        java.util.zip.ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: ByteArray) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
            entry(
                "META-INF/container.xml",
                """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles>
                        <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                      </rootfiles>
                    </container>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/content.opf",
                """
                    <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
                      <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <dc:title>CSS Reuse</dc:title>
                      </metadata>
                      <manifest>
                        <item id="chapter-1" href="chapter-1.xhtml" media-type="application/xhtml+xml"/>
                        <item id="chapter-2" href="chapter-2.xhtml" media-type="application/xhtml+xml"/>
                        <item id="shared-css" href="styles/shared.css" media-type="text/css"/>
                      </manifest>
                      <spine>
                        <itemref idref="chapter-1"/>
                        <itemref idref="chapter-2"/>
                      </spine>
                    </package>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-1.xhtml",
                """
                    <html>
                      <head><link rel="stylesheet" href="styles/shared.css"/></head>
                      <body><p class="lead">chapter one</p></body>
                    </html>
                """.trimIndent().encodeToByteArray(),
            )
            entry(
                "OEBPS/chapter-2.xhtml",
                """
                    <html>
                      <head><link rel="stylesheet" href="styles/shared.css"/></head>
                      <body><p class="lead">chapter two</p></body>
                    </html>
                """.trimIndent().encodeToByteArray(),
            )
            entry("OEBPS/styles/shared.css", ".lead { text-align: center; width: 80%; }".encodeToByteArray())
        }
        output.toByteArray()
    }
