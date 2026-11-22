package dev.plainpdf.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.plainpdf.app.ui.PlainPdfApp
import dev.plainpdf.app.ui.PlainPdfTheme

class MainActivity : ComponentActivity() {
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUri = intent?.pdfUriFromIntent()
        setContent {
            PlainPdfTheme {
                PlainPdfApp(
                    incomingUri = incomingUri,
                    onIncomingUriHandled = { incomingUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = intent.pdfUriFromIntent()
    }

    @Suppress("DEPRECATION")
    private fun Intent.pdfUriFromIntent(): Uri? =
        data ?: (getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
}
