package dev.plainpdf.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.plainpdf.app.core.LocalOcrEngine
import dev.plainpdf.app.core.MissingOcrLanguageDataException
import dev.plainpdf.app.core.PdfDocumentSession
import dev.plainpdf.app.core.PrivacyFinding
import dev.plainpdf.app.core.PrivacyScanResult
import dev.plainpdf.app.core.PrivacyScanner
import dev.plainpdf.app.core.SensitiveType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlainPdfApp(
    incomingUri: Uri?,
    onIncomingUriHandled: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<PdfDocumentSession?>(null) }
    var fileName by remember { mutableStateOf("") }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageIndex by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanResult by remember { mutableStateOf<PrivacyScanResult?>(null) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var ocrPageIndex by remember { mutableIntStateOf(0) }
    var showOcrDialog by remember { mutableStateOf(false) }
    var customPhrase by remember { mutableStateOf("") }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var selectedTypes by remember { mutableStateOf(SensitiveType.entries.filter { it != SensitiveType.CUSTOM }.toSet()) }

    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            openPdf(
                context = context,
                uri = uri,
                scope = scope,
                onLoading = { loading = it },
                onError = { errorMessage = it },
                onOpened = { newSession, name ->
                    session = newSession
                    fileName = name
                    pageCount = newSession.pageCount
                    pageIndex = 0
                    scanResult = null
                    showPrivacyDialog = false
                    extractedText = null
                    showOcrDialog = false
                    zoom = 1f
                    panX = 0f
                    panY = 0f
                }
            )
        }
    }

    val createRedacted = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { destination ->
        val activeSession = session
        val activeScan = scanResult
        if (destination != null && activeSession != null && activeScan != null) {
            scope.launch {
                exporting = true
                errorMessage = null
                try {
                    activeSession.exportRedactedCopy(
                        context = context,
                        destination = destination,
                        findings = activeScan.findings,
                        selectedTypes = selectedTypes
                    )
                    showPrivacyDialog = false
                    scanResult = null
                    customPhrase = ""
                    errorMessage = "Redacted copy saved. The original PDF was not changed."
                } catch (error: Exception) {
                    errorMessage = error.message ?: "Could not export the redacted PDF."
                } finally {
                    exporting = false
                }
            }
        }
    }

    LaunchedEffect(incomingUri) {
        val uri = incomingUri ?: return@LaunchedEffect
        openPdf(
            context = context,
            uri = uri,
            scope = scope,
            onLoading = { loading = it },
            onError = { errorMessage = it },
            onOpened = { newSession, name ->
                session = newSession
                fileName = name
                pageCount = newSession.pageCount
                pageIndex = 0
                scanResult = null
                extractedText = null
                showOcrDialog = false
                zoom = 1f
                panX = 0f
                panY = 0f
            }
        )
        onIncomingUriHandled()
    }

    DisposableEffect(session) {
        val closingSession = session
        onDispose {
            pageBitmap?.recycle()
            closingSession?.close()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.roundToPx().coerceIn(720, 2200) }

        LaunchedEffect(session, pageIndex, targetWidthPx) {
            val active = session ?: return@LaunchedEffect
            loading = true
            try {
                val rendered = active.renderPage(pageIndex, targetWidthPx)
                pageBitmap?.recycle()
                pageBitmap = rendered.bitmap
            } catch (error: Exception) {
                errorMessage = error.message ?: "Could not render this page."
            } finally {
                loading = false
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 20.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "PlainPDF",
                                fontSize = 23.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = (-0.6).sp
                            )
                            Text(
                                "JUST YOUR DOCUMENTS. NOTHING ELSE.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp,
                                letterSpacing = 1.2.sp
                            )
                        }
                        FilledTonalButton(onClick = { openPicker.launch(arrayOf("application/pdf")) }) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Open PDF")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    if (session != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text("PDF · $pageCount pages", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            IconButton(onClick = {
                                zoom = 1f; panX = 0f; panY = 0f
                            }) {
                                Icon(Icons.Outlined.FitScreen, contentDescription = "Fit page")
                            }
                        }
                    }
                    if (session != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val active = session ?: return@OutlinedButton
                                    val pageToRead = pageIndex
                                    scope.launch {
                                        scanning = true
                                        errorMessage = null
                                        try {
                                            extractedText = extractPageText(active, context, pageToRead)
                                            ocrPageIndex = pageToRead
                                            showOcrDialog = true
                                        } catch (error: MissingOcrLanguageDataException) {
                                            errorMessage = error.message
                                        } catch (error: Exception) {
                                            errorMessage = error.message ?: "OCR could not read this page."
                                        } finally {
                                            scanning = false
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !scanning && !exporting
                            ) {
                                Text("Extract text · OCR")
                            }
                            Button(
                                onClick = {
                                    val active = session ?: return@Button
                                    scope.launch {
                                        scanning = true
                                        errorMessage = null
                                        try {
                                            val result = scanPdf(active, context)
                                            scanResult = result
                                            selectedTypes = SensitiveType.entries
                                                .filter { it != SensitiveType.CUSTOM && result.count(it) > 0 }
                                                .toSet()
                                            customPhrase = ""
                                            showPrivacyDialog = true
                                        } catch (error: MissingOcrLanguageDataException) {
                                            errorMessage = error.message
                                        } catch (error: Exception) {
                                            errorMessage = error.message ?: "Privacy scan failed."
                                        } finally {
                                            scanning = false
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !scanning && !exporting
                            ) {
                                Icon(Icons.Outlined.Shield, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(7.dp))
                                Text("Privacy scan")
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (session != null) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { if (pageIndex > 0) pageIndex--; zoom = 1f; panX = 0f; panY = 0f },
                                enabled = pageIndex > 0
                            ) {
                                Icon(Icons.Outlined.ArrowBack, contentDescription = "Previous page")
                            }
                            Text("${pageIndex + 1} / $pageCount", fontWeight = FontWeight.Medium)
                            IconButton(
                                onClick = { if (pageIndex < pageCount - 1) pageIndex++; zoom = 1f; panX = 0f; panY = 0f },
                                enabled = pageIndex < pageCount - 1
                            ) {
                                Icon(Icons.Outlined.ArrowForward, contentDescription = "Next page")
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding).background(ColorBackground),
                contentAlignment = Alignment.Center
            ) {
                when {
                    session == null -> EmptyState(onOpen = { openPicker.launch(arrayOf("application/pdf")) })
                    pageBitmap != null -> {
                        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                            zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                            panX += panChange.x
                            panY += panChange.y
                        }
                        Image(
                            bitmap = pageBitmap!!.asImageBitmap(),
                            contentDescription = "PDF page ${pageIndex + 1}",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = panX
                                    translationY = panY
                                }
                                .transformable(transformState),
                        )
                    }
                    else -> CircularProgressIndicator()
                }
                if (loading && session != null) {
                    CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                }
                if (scanning) {
                    Surface(
                        modifier = Modifier.align(Alignment.Center),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp
                    ) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Processing on this device…")
                            Text("Nothing is uploaded.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    if (showOcrDialog && extractedText != null) {
        AlertDialog(
            onDismissRequest = {
                showOcrDialog = false
                extractedText = null
            },
            title = { Text("OCR text · page ${ocrPageIndex + 1}") },
            text = {
                val text = extractedText.orEmpty()
                if (text.isBlank()) {
                    Text("No readable text was found on this page.")
                } else {
                    Text(
                        text,
                        modifier = Modifier.fillMaxWidth().height(360.dp).verticalScroll(rememberScrollState()),
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("OCR text", extractedText.orEmpty()))
                    showOcrDialog = false
                    extractedText = null
                    errorMessage = "OCR text copied to clipboard."
                }) { Text("Copy text") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showOcrDialog = false
                    extractedText = null
                }) { Text("Close") }
            }
        )
    }

    if (showPrivacyDialog && scanResult != null) {
        PrivacyDialog(
            result = scanResult!!,
            selectedTypes = selectedTypes,
            onSelectedTypesChange = { selectedTypes = it },
            customPhrase = customPhrase,
            onCustomPhraseChange = { value ->
                customPhrase = value
                scanResult = scanResult?.withCustomPhrase(value)
                val hasCustomMatch = (scanResult?.count(SensitiveType.CUSTOM) ?: 0) > 0
                selectedTypes = if (hasCustomMatch) selectedTypes + SensitiveType.CUSTOM
                    else selectedTypes - SensitiveType.CUSTOM
            },
            exporting = exporting,
            onCancel = {
                showPrivacyDialog = false
                scanResult = null
                customPhrase = ""
            },
            onExport = {
                val selectedCount = scanResult!!.findings.count { it.type in selectedTypes }
                if (selectedCount == 0) {
                    errorMessage = "Select at least one detected item to redact."
                } else {
                    createRedacted.launch("${fileName.substringBeforeLast('.', fileName)}-redacted.pdf")
                }
            }
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("PlainPDF") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("Done") } }
        )
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(58.dp))
        Spacer(Modifier.height(18.dp))
        Text("Nothing open", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(
            "Choose a PDF from your device. It stays on your device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onOpen) { Text("Choose a PDF") }
    }
}

@Composable
private fun PrivacyDialog(
    result: PrivacyScanResult,
    selectedTypes: Set<SensitiveType>,
    onSelectedTypesChange: (Set<SensitiveType>) -> Unit,
    customPhrase: String,
    onCustomPhraseChange: (String) -> Unit,
    exporting: Boolean,
    onCancel: () -> Unit,
    onExport: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = {
            Column {
                Text("Review privacy findings", fontWeight = FontWeight.SemiBold)
                Text(
                    "${result.findings.size} suggestions across ${result.pageCount} pages",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        },
        text = {
            Column {
                Text(
                    "Choose what to hide. OCR can miss things, so review the document yourself before sharing.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                SensitiveType.entries.forEach { type ->
                    val count = result.count(type)
                    if (count > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = type in selectedTypes,
                                onCheckedChange = { checked ->
                                    onSelectedTypesChange(if (checked) selectedTypes + type else selectedTypes - type)
                                }
                            )
                            Text(type.title, modifier = Modifier.weight(1f))
                            Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customPhrase,
                    onValueChange = onCustomPhraseChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Also hide exact text / a name") },
                    placeholder = { Text("e.g. Full name or reference ID") },
                    singleLine = true
                )
                Text(
                    "Custom text is matched exactly (case-insensitive) against OCR lines. Names are not guessed automatically.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (result.findings.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("DETECTIONS", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.findings.take(7).forEach { finding ->
                        Text(
                            "${finding.type.title} · page ${finding.pageIndex + 1} · ${previewValue(finding.value, finding.type)}",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (result.findings.size > 7) {
                        Text("…and ${result.findings.size - 7} more", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    "Export creates a new flattened PDF. Selectable text and links will not be preserved; the original stays untouched.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onExport, enabled = !exporting) {
                if (exporting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text("Export redacted copy")
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !exporting) { Text("Cancel") } }
    )
}

private fun openPdf(
    context: Context,
    uri: Uri,
    scope: kotlinx.coroutines.CoroutineScope,
    onLoading: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onOpened: (PdfDocumentSession, String) -> Unit
) {
    scope.launch {
        onLoading(true)
        try {
            val newSession = PdfDocumentSession.open(context, uri)
            onOpened(newSession, queryDisplayName(context, uri))
        } catch (error: Exception) {
            onError(error.message ?: "Could not open this PDF. It may be encrypted or damaged.")
        } finally {
            onLoading(false)
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return it.getString(index) ?: "Document.pdf"
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
}

private suspend fun extractPageText(
    session: PdfDocumentSession,
    context: Context,
    pageIndex: Int
): String = withContext(Dispatchers.IO) {
    val rendered = session.renderPage(pageIndex, 1800)
    val engine = LocalOcrEngine(context)
    try {
        engine.initialize()
        engine.recognizeText(rendered.bitmap)
    } finally {
        engine.close()
        rendered.bitmap.recycle()
    }
}

private suspend fun scanPdf(
    session: PdfDocumentSession,
    context: Context
): PrivacyScanResult = withContext(Dispatchers.IO) {
    val engine = LocalOcrEngine(context)
    val findings = mutableListOf<PrivacyFinding>()
    val pageOcr = mutableListOf<dev.plainpdf.app.core.PageOcrData>()
    try {
        engine.initialize()
        for (pageIndex in 0 until session.pageCount) {
            val rendered = session.renderPage(pageIndex, 1800)
            try {
                val words = engine.recognize(rendered.bitmap)
                findings += PrivacyScanner.scanPage(
                    pageIndex = pageIndex,
                    bitmapWidth = rendered.bitmap.width,
                    bitmapHeight = rendered.bitmap.height,
                    words = words
                )
                pageOcr += dev.plainpdf.app.core.PageOcrData(
                    pageIndex = pageIndex,
                    bitmapWidth = rendered.bitmap.width,
                    bitmapHeight = rendered.bitmap.height,
                    words = words
                )
            } finally {
                rendered.bitmap.recycle()
            }
        }
    } finally {
        engine.close()
    }
    PrivacyScanResult(findings, session.pageCount, pageOcr)
}

private fun previewValue(value: String, type: SensitiveType): String = when (type) {
    SensitiveType.EMAIL -> value.substringBefore('@').take(1) + "•••@" + value.substringAfter('@', "•••")
    SensitiveType.PHONE -> "••••••" + value.takeLast(4)
    SensitiveType.AADHAAR -> "•••• •••• " + value.filter(Char::isDigit).takeLast(4)
    SensitiveType.PAN -> "••••••" + value.takeLast(4)
    SensitiveType.CUSTOM -> value
}

private val ColorBackground = androidx.compose.ui.graphics.Color(0xFF252A33)
