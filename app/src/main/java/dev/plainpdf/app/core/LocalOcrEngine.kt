package dev.plainpdf.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

 data class OcrWord(
    val text: String,
    val bounds: Rect,
    val confidence: Float
)

class MissingOcrLanguageDataException : Exception(
    "OCR language data is missing. Bundle eng.traineddata in app/src/main/assets/tessdata/ and rebuild."
)

/** Tesseract runs locally. Its language model is copied from APK assets to private app storage. */
class LocalOcrEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val dataRoot = File(appContext.filesDir, "tesseract")
    private val api = TessBaseAPI()
    private var initialized = false

    fun initialize() {
        if (initialized) return
        installBundledLanguageData()
        val availableLanguages = listOf("eng", "hin").filter { language ->
            File(dataRoot, "tessdata/$language.traineddata").isFile
        }
        initialized = api.init(dataRoot.absolutePath, availableLanguages.joinToString("+"))
        if (!initialized) {
            api.recycle()
            throw MissingOcrLanguageDataException()
        }
    }

    fun recognizeText(bitmap: Bitmap): String {
        check(initialized) { "OCR engine is not initialized." }
        api.setImage(bitmap)
        return api.getUTF8Text().orEmpty()
    }

    fun recognize(bitmap: Bitmap): List<OcrWord> {
        check(initialized) { "OCR engine is not initialized." }
        api.setImage(bitmap)
        val iterator: ResultIterator = api.getResultIterator() ?: return emptyList()
        val results = mutableListOf<OcrWord>()
        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(level)?.trim().orEmpty()
                val rect = iterator.getBoundingRect(level)
                val confidence = iterator.confidence(level)
                if (text.isNotBlank() && rect != null && rect.width() > 0 && rect.height() > 0) {
                    results += OcrWord(text, Rect(rect), confidence)
                }
            } while (iterator.next(level))
        } finally {
            iterator.delete()
        }
        return results
    }

    private fun installBundledLanguageData() {
        val assetNames = appContext.assets.list("tessdata").orEmpty()
        val trainedFiles = assetNames.filter { it.endsWith(".traineddata") }
        if ("eng.traineddata" !in trainedFiles) throw MissingOcrLanguageDataException()

        val tessDataDirectory = File(dataRoot, "tessdata")
        if (!tessDataDirectory.exists() && !tessDataDirectory.mkdirs()) {
            throw IllegalStateException("Could not create private OCR data directory.")
        }

        trainedFiles.forEach { fileName ->
            val destination = File(tessDataDirectory, fileName)
            if (!destination.exists() || destination.length() == 0L) {
                appContext.assets.open("tessdata/$fileName").use { input ->
                    FileOutputStream(destination).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    override fun close() {
        if (initialized) {
            api.recycle()
            initialized = false
        }
    }
}
