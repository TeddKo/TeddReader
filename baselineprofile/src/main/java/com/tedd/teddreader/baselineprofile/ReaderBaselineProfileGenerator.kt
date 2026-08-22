package com.tedd.teddreader.baselineprofile

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Records the classes and methods a reader touches on the way to its first page.
 *
 * Opening a book measured ~2.2 s from tap to first page on a real device while the data path finished
 * in ~230 ms, and it was ~2.2 s for three books of very different sizes — the remainder is the reader's
 * composable tree being loaded and compiled for the first time. A profile is what lets that work be
 * done ahead of time instead of while the reader waits.
 *
 * The journey has to include a page turn: the pager, the page surface and the text layout are only
 * reached once a page actually moves, and those are the classes worth having compiled.
 */
class ReaderBaselineProfileGenerator {
    /**
     * Runs the `BaselineProfileRule`'s instrumentation against the installed app to record which
     * classes and methods each journey below touches.
     */
    @get:Rule
    val rule = BaselineProfileRule()

    /**
     * The main reading journey this generator exists for: opens the sample book bundled with this
     * module and turns a few pages, so the profile records the pager, the page surface, and the
     * text layout classes that are only reached once a page actually moves.
     *
     * The book is carried by this module and published to the device fresh on every iteration,
     * rather than looked for in whatever the device's library already holds. Generation uninstalls
     * the app when it finishes, so an earlier run's imported book is gone by the next one — a
     * journey that depended on finding one recorded only the library screen and nothing of the
     * reader at all, and did so silently, since a profile with no reader classes in it still
     * generates and still builds without complaint.
     *
     * Waiting on the page counter — the text containing `" / "` that shows the current page out of
     * the total — works because it is the reader's own "I have a page" signal: reaching it means
     * the import, the first measurement, and the first page have all completed, which is the whole
     * of what this profile needs to have happened before it can start recording page turns.
     */
    @Test
    fun openLibraryAndReadAFewPages() = rule.collect(packageName = PackageName) {
        pressHome()

        startActivityAndWait(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(publishSampleBook(), EpubMimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage(PackageName)
            },
        )

        device.wait(Until.hasObject(By.textContains(" / ")), 60_000)
        repeat(3) {
            device.click(device.displayWidth * 4 / 5, device.displayHeight / 2)
            device.waitForIdle()
        }
    }

    /**
     * Launching to the library and stopping there, which is what a startup profile is for: it orders the
     * dex so the classes a launch actually reads sit together and are read in one sweep. Marking the
     * reading journey above as a startup profile instead put the whole reader in it, and a startup
     * profile that names everything orders nothing.
     */
    @Test
    fun launchToTheLibrary() = rule.collect(
        packageName = PackageName,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }

    /**
     * Puts this module's sample book in the device's Downloads so the app can be handed a readable URI
     * for it. MediaStore rather than a plain file path: the app holds no storage permission, and a
     * `file://` URI it cannot open would fail the same way an absent book does.
     *
     * Replaces its own earlier copy instead of accumulating one per iteration.
     */
    private fun publishSampleBook(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().context
        val resolver = context.contentResolver
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(SampleBookName),
        )
        val uri = requireNotNull(
            resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, SampleBookName)
                    put(MediaStore.Downloads.MIME_TYPE, EpubMimeType)
                },
            ),
        ) { "Downloads would not accept $SampleBookName" }
        resolver.openOutputStream(uri).use { destination ->
            requireNotNull(destination) { "Downloads gave no stream for $SampleBookName" }
            context.assets.open(SampleBookAsset).use { source -> source.copyTo(destination) }
        }
        return uri
    }
}

/** The application id of the installed build this generator instruments and collects a profile for. */
private const val PackageName = "com.tedd.teddreader"

/** MIME type used both to publish the sample book to Downloads and to open it via `ACTION_VIEW`. */
private const val EpubMimeType = "application/epub+zip"

/**
 * File name of the sample EPUB bundled as an asset with this module, read by
 * [ReaderBaselineProfileGenerator.publishSampleBook].
 */
private const val SampleBookAsset = "sample.epub"

/**
 * Display name the sample book is published under in the device's Downloads, used by
 * [ReaderBaselineProfileGenerator.publishSampleBook] both to delete any earlier copy under the same
 * name before republishing and to name the new one.
 */
private const val SampleBookName = "tedd-reader-baseline-profile-sample.epub"
