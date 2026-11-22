package dev.plainpdf.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import kotlin.math.roundToInt

 data class RenderedPdfPage(
    val bitmap: Bitmap,
    val pageWidthPoints: Int,
    val pageHeightPoints: Int
)

class PdfDocumentSession private constructor(
    private val renderer: PdfRenderer
) : Closeable {
    val pageCount: Int get() = renderer.pageCount

    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage =
        withContext(Dispatchers.IO) {
            require(pageIndex in 0 until renderer.pageCount) { "Page index is out of range." }
            val page = renderer.openPage(pageIndex)
            try {
                val width = targetWidthPx.coerceIn(720, 2200)
                val scale = width.toFloat() / page.width.toFloat()
                val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                RenderedPdfPage(bitmap, page.width, page.height)
            } finally {
                page.close()
            }
        }

    /**
     * Creates a new image-based PDF. The original is never modified. The source
     * page's PDF objects, text layer and metadata are not copied into the export.
     */
    suspend fun exportRedactedCopy(
        context: Context,
        destination: Uri,
        findings: List<PrivacyFinding>,
        selectedTypes: Set<SensitiveType>
    ) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(destination, "w")
            ?: throw IOException("Could not open the destination for writing.")
        val document = PdfDocument()
        try {
            for (pageIndex in 0 until renderer.pageCount) {
                val rendered = renderPage(pageIndex, REDACTION_RENDER_WIDTH)
                try {
                    val pageWidth = rendered.pageWidthPoints.coerceIn(1, 14_400)
                    val pageHeight = rendered.pageHeightPoints.coerceIn(1, 14_400)
                    val pageInfo = PdfDocument.PageInfo.Builder(
                        pageWidth,
                        pageHeight,
                        pageIndex + 1
                    ).create()
                    val outputPage = document.startPage(pageInfo)
                    val target = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
                    // Burn the selected areas into the source bitmap BEFORE embedding it.
                    // Drawing a black rectangle on the PDF canvas after this point would
                    // only create a removable overlay and would not be secure redaction.
                    val redactionCanvas = android.graphics.Canvas(rendered.bitmap)
                    val redactionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                    }
                    findings.asSequence()
                        .filter { it.pageIndex == pageIndex }
                        .filter { it.type in selectedTypes }
                        .forEach { finding ->
                            val normalized = finding.normalizedBounds
                            val padX = (normalized.width() * 0.10f).coerceAtLeast(0.002f)
                            val padY = (normalized.height() * 0.22f).coerceAtLeast(0.002f)
                            val rect = RectF(
                                (normalized.left - padX).coerceAtLeast(0f) * rendered.bitmap.width,
                                (normalized.top - padY).coerceAtLeast(0f) * rendered.bitmap.height,
                                (normalized.right + padX).coerceAtMost(1f) * rendered.bitmap.width,
                                (normalized.bottom + padY).coerceAtMost(1f) * rendered.bitmap.height
                            )
                            redactionCanvas.drawRect(rect, redactionPaint)
                        }

                    outputPage.canvas.drawBitmap(
                        rendered.bitmap,
                        null,
                        target,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                    document.finishPage(outputPage)
                } finally {
                    rendered.bitmap.recycle()
                }
            }
            document.writeTo(output)
            output.flush()
        } finally {
            runCatching { document.close() }
            runCatching { output.close() }
        }
    }

    override fun close() {
        renderer.close()
    }

    companion object {
        private const val REDACTION_RENDER_WIDTH = 1800

        suspend fun open(context: Context, uri: Uri): PdfDocumentSession =
            withContext(Dispatchers.IO) {
                val descriptor: ParcelFileDescriptor =
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IOException("Android could not open this PDF.")
                try {
                    PdfDocumentSession(PdfRenderer(descriptor))
                } catch (error: Throwable) {
                    runCatching { descriptor.close() }
                    throw error
                }
            }
    }
}
