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

class MainActivity : ComponentActivity() {
    private var externalImportRequest by mutableStateOf<ExternalDocumentImportRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        externalImportRequest = androidExternalDocumentImportRequest(intent, this)
        setContent { TeddReaderApp(initialExternalImportRequest = externalImportRequest) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        externalImportRequest = androidExternalDocumentImportRequest(intent, this)
    }
}

@Preview
@Composable
private fun AppAndroidPreview() {
    TeddReaderApp()
}
