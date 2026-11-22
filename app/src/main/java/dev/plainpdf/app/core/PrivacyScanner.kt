package dev.plainpdf.app.core

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import java.util.UUID

@Suppress("unused")
enum class SensitiveType(val title: String) {
    EMAIL("Email addresses"),
    PHONE("Phone numbers"),
    AADHAAR("Aadhaar numbers"),
    PAN("PAN numbers"),
    ENROLLMENT("Enrollment / student ID numbers"),
    STUDENT_NAME("Student names (labelled fields)"),
    PINCODE("Postal PIN codes"),
    CUSTOM("Custom text / names")
}

data class PrivacyFinding(
    val id: String = UUID.randomUUID().toString(),
    val pageIndex: Int,
    val type: SensitiveType,
    val value: String,
    /** Coordinates are normalized to the page bitmap, from 0f to 1f. */
    val normalizedBounds: RectF
)

data class PageOcrData(
    val pageIndex: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val words: List<OcrWord>
)

data class PrivacyScanResult(
    val findings: List<PrivacyFinding>,
    val pageCount: Int,
    val pageOcr: List<PageOcrData> = emptyList()
) {
    fun count(type: SensitiveType): Int = findings.count { it.type == type }

    fun withCustomPhrase(phrase: String): PrivacyScanResult {
        val structured = findings.filter { it.type != SensitiveType.CUSTOM }
        val custom = pageOcr.flatMap { page ->
            PrivacyScanner.scanPage(
                pageIndex = page.pageIndex,
                bitmapWidth = page.bitmapWidth,
                bitmapHeight = page.bitmapHeight,
                words = page.words,
                customPhrase = phrase
            ).filter { it.type == SensitiveType.CUSTOM }
        }
        return copy(findings = structured + custom)
    }
}

/** Pattern matching is intentionally conservative. Names are handled via user-entered exact text. */
object PrivacyScanner {
    private val patterns = linkedMapOf(
        SensitiveType.EMAIL to Regex("(?i)\\b[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b"),
        SensitiveType.PHONE to Regex("(?<!\\d)(?:\\+?91[\\s-]?)?[6-9]\\d{4}(?:[\\s-]*\\d){5}(?!\\d)"),
        SensitiveType.AADHAAR to Regex("(?<!\\d)\\d{4}(?:[\\s-]?\\d{4}){2}(?!\\d)"),
        SensitiveType.PAN to Regex("(?i)\\b[A-Z]{5}\\d{4}[A-Z]\\b")
    )

    // Label-driven rules avoid treating arbitrary 6- or 10-digit numbers as private IDs.
    private val enrollmentLabel = Regex(
        "(?i)(?:enrollment|enrolment)(?:\\s*(?:no\\.?|number|#))?|अनुक्रमांक|नामांकन\\s*संख्या"
    )
    private val studentNameLabel = Regex(
        "(?i)(?:name\\s+of\\s+(?:the\\s+)?student|student(?:'s)?\\s+name|परीक्षार्थी\\s*का\\s*नाम|छात्र\\s*का\\s*नाम)"
    )
    private val pincodeLabel = Regex("(?i)(?:pin\\s*code|pincode|postal\\s*code|पिनकोड)")

    fun scanPage(
        pageIndex: Int,
        bitmapWidth: Int,
        bitmapHeight: Int,
        words: List<OcrWord>,
        customPhrase: String = ""
    ): List<PrivacyFinding> {
        if (words.isEmpty() || bitmapWidth <= 0 || bitmapHeight <= 0) return emptyList()
        val lines = groupIntoLines(words)
        val findings = mutableListOf<PrivacyFinding>()

        lines.forEach { line ->
            val lineText = StringBuilder()
            val spans = mutableListOf<WordSpan>()
            line.forEachIndexed { index, word ->
                if (index > 0) lineText.append(' ')
                val start = lineText.length
                lineText.append(word.text)
                spans += WordSpan(start, lineText.length, word)
            }

            // Normalize Devanagari and Arabic-Indic digits to ASCII without changing
            // string length, so OCR word offsets remain aligned with the source text.
            val normalizedLineText = normalizeDigits(lineText.toString())
            val aadhaarMatches = patterns.getValue(SensitiveType.AADHAAR)
                .findAll(normalizedLineText).toList()
            val enrollmentLabelMatch = enrollmentLabel.find(normalizedLineText)
            patterns.forEach { (type, regex) ->
                regex.findAll(normalizedLineText).forEach matchLoop@ { match ->
                    // A 12-digit Aadhaar string can otherwise look like a 10-digit
                    // phone number if OCR inserts spaces. Keep the larger ID as one finding.
                    if (type == SensitiveType.PHONE && aadhaarMatches.any { aadhaar ->
                            match.range.first <= aadhaar.range.last && match.range.last >= aadhaar.range.first
                        }) return@matchLoop
                    // Do not classify a labelled enrollment ID as a phone number.
                    if (type == SensitiveType.PHONE && enrollmentLabelMatch != null &&
                        match.range.first > enrollmentLabelMatch.range.last) return@matchLoop
                    addFindingForRange(findings, pageIndex, type, match.value, match.range.first,
                        match.range.last + 1, spans, bitmapWidth, bitmapHeight)
                }
            }

            // Enrollment IDs are detected only when OCR also sees an enrollment label.
            enrollmentLabelMatch?.let { label ->
                val valueStart = skipFieldSeparators(normalizedLineText, label.range.last + 1)
                val valueMatch = Regex("[0-9][0-9\\s-]{7,18}[0-9]")
                    .find(normalizedLineText, valueStart)
                if (valueMatch != null) {
                    val digits = valueMatch.value.filter(Char::isDigit)
                    if (digits.length in 9..16) {
                        addFindingForRange(findings, pageIndex, SensitiveType.ENROLLMENT, valueMatch.value,
                            valueMatch.range.first, valueMatch.range.last + 1, spans, bitmapWidth, bitmapHeight)
                    }
                }
            }

            // Only redact a name when it follows an explicit student-name label.
            studentNameLabel.find(normalizedLineText)?.let { label ->
                val valueStart = skipFieldSeparators(normalizedLineText, label.range.last + 1)
                val nameMatch = Regex("[\\p{L}][\\p{L}.'-]*(?:\\s+[\\p{L}][\\p{L}.'-]*){0,3}")
                    .find(normalizedLineText, valueStart)
                if (nameMatch != null) {
                    addFindingForRange(findings, pageIndex, SensitiveType.STUDENT_NAME, nameMatch.value,
                        nameMatch.range.first, nameMatch.range.last + 1, spans, bitmapWidth, bitmapHeight)
                }
            }

            pincodeLabel.find(normalizedLineText)?.let { label ->
                val valueStart = skipFieldSeparators(normalizedLineText, label.range.last + 1)
                val pinMatch = Regex("[0-9]{6}").find(normalizedLineText, valueStart)
                if (pinMatch != null) {
                    addFindingForRange(findings, pageIndex, SensitiveType.PINCODE, pinMatch.value,
                        pinMatch.range.first, pinMatch.range.last + 1, spans, bitmapWidth, bitmapHeight)
                }
            }

            val cleanCustom = customPhrase.trim()
            if (cleanCustom.length >= 2) {
                Regex(Regex.escape(cleanCustom), RegexOption.IGNORE_CASE).findAll(lineText).forEach { match ->
                    val matched = spans.filter { it.start < match.range.last + 1 && it.endExclusive > match.range.first }
                    if (matched.isNotEmpty()) {
                        findings += toFinding(
                            pageIndex, SensitiveType.CUSTOM, match.value,
                            matched.map { it.word.bounds }, bitmapWidth, bitmapHeight
                        )
                    }
                }
            }
        }

        return findings.distinctBy {
            Triple(it.type, it.value.lowercase(), listOf(it.normalizedBounds.left, it.normalizedBounds.top))
        }
    }

    private fun addFindingForRange(
        findings: MutableList<PrivacyFinding>, pageIndex: Int, type: SensitiveType, value: String,
        start: Int, endExclusive: Int, spans: List<WordSpan>, bitmapWidth: Int, bitmapHeight: Int
    ) {
        val matched = spans.filter { it.start < endExclusive && it.endExclusive > start }
        if (matched.isNotEmpty()) {
            findings += toFinding(pageIndex, type, value, matched.map { it.word.bounds }, bitmapWidth, bitmapHeight)
        }
    }

    private fun skipFieldSeparators(text: String, start: Int): Int {
        var index = start.coerceIn(0, text.length)
        while (index < text.length && (text[index].isWhitespace() || text[index] in ":：|/#.-–—")) index++
        return index
    }

    private fun groupIntoLines(words: List<OcrWord>): List<List<OcrWord>> {
        val result = mutableListOf<MutableList<OcrWord>>()
        words.sortedWith(compareBy<OcrWord> { it.bounds.centerY() }.thenBy { it.bounds.left })
            .forEach { word ->
                val candidate = result.lastOrNull()
                val avgY = candidate?.map { it.bounds.centerY() }?.average()?.toFloat()
                val avgHeight = candidate?.map { it.bounds.height() }?.average()?.toFloat()
                val tolerance = max(word.bounds.height(), (avgHeight ?: word.bounds.height().toFloat()).toInt()) * 0.65f
                if (candidate != null && avgY != null && abs(avgY - word.bounds.centerY()) <= tolerance) {
                    candidate += word
                } else {
                    result += mutableListOf(word)
                }
            }
        return result.map { it.sortedBy { word -> word.bounds.left } }
    }

    private fun toFinding(
        pageIndex: Int,
        type: SensitiveType,
        value: String,
        rects: List<Rect>,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): PrivacyFinding {
        val left = rects.minOf { it.left }.toFloat()
        val top = rects.minOf { it.top }.toFloat()
        val right = rects.maxOf { it.right }.toFloat()
        val bottom = rects.maxOf { it.bottom }.toFloat()
        return PrivacyFinding(
            pageIndex = pageIndex,
            type = type,
            value = value,
            normalizedBounds = RectF(
                (left / bitmapWidth).coerceIn(0f, 1f),
                (top / bitmapHeight).coerceIn(0f, 1f),
                (right / bitmapWidth).coerceIn(0f, 1f),
                (bottom / bitmapHeight).coerceIn(0f, 1f)
            )
        )
    }

    private fun normalizeDigits(value: String): String = buildString(value.length) {
        value.forEach { character ->
            val digit = when (character) {
                in '०'..'९' -> character.code - '०'.code
                in '٠'..'٩' -> character.code - '٠'.code
                else -> -1
            }
            append(if (digit >= 0) ('0'.code + digit).toChar() else character)
        }
    }

    private data class WordSpan(val start: Int, val endExclusive: Int, val word: OcrWord)
}
