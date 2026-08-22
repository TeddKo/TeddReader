package com.tedd.teddreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import com.tedd.teddreader.app.reader.TeddReaderApp
import com.tedd.teddreader.app.reader.importer.ExternalDocumentImportRequest
import com.tedd.teddreader.app.reader.importer.androidExternalDocumentImportRequest

/**
 * The Android entry point for TeddReader, and the only `Activity` the app declares. Every screen
 * the app shows — home, library, reader, search, bookmarks, document info, settings — is a
 * Composable reached through [TeddReaderApp]'s own navigation host, not a separate Activity, so
 * this class exists only to host that Compose tree and to bridge Android's Intent-based document
 * delivery (the manifest's `VIEW`/`SEND` filters used when another app hands TeddReader a file, or
 * a share-sheet target) into the [ExternalDocumentImportRequest] the composition understands.
 */
class MainActivity : ComponentActivity() {

    /**
     * The document-open request carried by whichever [Intent] most recently started or retargeted
     * this activity, or null when the activity was launched plainly from the app launcher with no
     * document attached. Held as Compose state, not a local value, so that [onNewIntent] handing
     * this a fresh request while the activity is already on screen recomposes the content with the
     * new document instead of the request being silently dropped.
     */
    private var externalImportRequest by mutableStateOf<ExternalDocumentImportRequest?>(null)

    /**
     * Enables edge-to-edge drawing before the framework's own `onCreate` runs, since the insets
     * must be in place for the very first composed frame, then reads whatever document the
     * launching intent carries and sets the Compose content to [TeddReaderApp] seeded with it.
     *
     * @param savedInstanceState the framework's restored instance state; unused here because every
     *   piece of state this app needs to survive a process restart (reading position, navigation
     *   back stack) is recovered by `rememberSaveable` inside the Compose tree rather than by this
     *   activity.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        externalImportRequest = androidExternalDocumentImportRequest(intent, this)
        setContent { TeddReaderApp(initialExternalImportRequest = externalImportRequest) }
    }

    /**
     * Handles a document-open intent redelivered to this already-running activity instance — for
     * example, a second "Open with TeddReader" while the app is already in the foreground — by
     * re-parsing it into [externalImportRequest] so the composition picks up the newly requested
     * document instead of the one the activity was originally created with.
     *
     * @param intent the redelivered intent, also stored via `setIntent` so a later configuration
     *   change reads the same document rather than reverting to the original launch intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalImportRequest = androidExternalDocumentImportRequest(intent, this)
    }
}

/**
 * Android Studio design-time preview of [TeddReaderApp] with no external import request, so the
 * preview renders the app's ordinary launch state — the home screen — rather than a reader screen
 * for a document that does not exist at preview time.
 */
@Preview
@Composable
private fun AppAndroidPreview() {
    TeddReaderApp()
}
