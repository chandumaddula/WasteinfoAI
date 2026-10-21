package com.wasteinfoai.ai.barcode

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Structured evidence extracted from on-device packaging text and recycling symbol OCR.
 */
data class OcrPackagingEvidence(
    val resinCode: Int?,
    val materialCode: String?,
    val materialName: String?,
    val coarseCategory: String?,
    val detectedKeywords: List<String>,
    val confidence: Float,
    val rawMatchedText: String
)

/**
 * On-device OCR service utilizing Google ML Kit Latin Text Recognition.
 * Identifies Resin Identification Codes (RIC 1–7), recycling triangle markings,
 * and manufacturer disposal instructions directly from physical packaging.
 */
class PackagingOcrService(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
) : AutoCloseable {

    companion object {
        private const val TAG = "PackagingOcrService"

        // Resin Identification Code patterns (1 to 7)
        private val RIC_SYMBOL_REGEX = Pattern.compile(
            """\b(?:RIC|RESIN)?\s*#?\s*([1-7])\b|♳|♴|♵|♶|♷|♸|♹""",
            Pattern.CASE_INSENSITIVE
        )

        private val RESIN_NAME_REGEX = Pattern.compile(
            """\b(PETE?|HDPE|PVC|LDPE|PP|PS|OTHER)\s*(?:0?[1-7])?\b""",
            Pattern.CASE_INSENSITIVE
        )

        private val INSTRUCTION_KEYWORDS = listOf(
            "100% RECYCLABLE", "RECYCLABLE", "PLEASE RECYCLE", "DO NOT LITTER",
            "RINSE BEFORE RECYCLING", "CRUSH BOTTLE AFTER USE", "FLATTEN BOX",
            "BIODEGRADABLE", "COMPOSTABLE", "E-WASTE"
        )

        val RIC_METADATA = mapOf(
            1 to Triple("PET 01", "Polyethylene Terephthalate (PET)", "plastic"),
            2 to Triple("HDPE 02", "High-Density Polyethylene (HDPE)", "plastic"),
            3 to Triple("PVC 03", "Polyvinyl Chloride (PVC)", "plastic"),
            4 to Triple("LDPE 04", "Low-Density Polyethylene (LDPE)", "plastic"),
            5 to Triple("PP 05", "Polypropylene (PP)", "plastic"),
            6 to Triple("PS 06", "Polystyrene (PS)", "plastic"),
            7 to Triple("OTHER 07", "Other / Multi-Layer Polymer", "plastic")
        )
    }

    /**
     * Extracts recycling symbols and packaging text markings from a [bitmap].
     */
    fun scanPackagingText(bitmap: Bitmap): OcrPackagingEvidence? {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(inputImage)
            val result = Tasks.await(task, 2500, TimeUnit.MILLISECONDS)
            val fullText = result.text.uppercase()

            if (fullText.isBlank()) return null

            var detectedRic: Int? = null
            var matchedText = ""

            // Check unicode recycling symbols
            when {
                fullText.contains("♳") -> detectedRic = 1
                fullText.contains("♴") -> detectedRic = 2
                fullText.contains("♵") -> detectedRic = 3
                fullText.contains("♶") -> detectedRic = 4
                fullText.contains("♷") -> detectedRic = 5
                fullText.contains("♸") -> detectedRic = 6
                fullText.contains("♹") -> detectedRic = 7
            }

            // Check name regex
            if (detectedRic == null) {
                val nameMatcher = RESIN_NAME_REGEX.matcher(fullText)
                if (nameMatcher.find()) {
                    val codeStr = nameMatcher.group(1)?.uppercase() ?: ""
                    detectedRic = when (codeStr) {
                        "PET", "PETE" -> 1
                        "HDPE" -> 2
                        "PVC" -> 3
                        "LDPE" -> 4
                        "PP" -> 5
                        "PS" -> 6
                        "OTHER" -> 7
                        else -> null
                    }
                    if (detectedRic != null) {
                        matchedText = nameMatcher.group(0) ?: ""
                    }
                }
            }

            // Check numeric symbol regex
            if (detectedRic == null) {
                val ricMatcher = RIC_SYMBOL_REGEX.matcher(fullText)
                while (ricMatcher.find()) {
                    val digitStr = ricMatcher.group(1)
                    val digit = digitStr?.toIntOrNull()
                    if (digit != null && digit in 1..7) {
                        // Validate surrounding context to avoid false matching generic prices or counts
                        val start = Math.max(0, ricMatcher.start() - 10)
                        val end = Math.min(fullText.length, ricMatcher.end() + 10)
                        val window = fullText.substring(start, end)
                        if (window.contains("RECYCLE") || window.contains("RIC") || window.contains("TRIANGLE") || window.contains("PLASTIC") || window.contains("POLY")) {
                            detectedRic = digit
                            matchedText = ricMatcher.group(0) ?: ""
                            break
                        }
                    }
                }
            }

            // Check keywords
            val foundKeywords = INSTRUCTION_KEYWORDS.filter { fullText.contains(it) }

            if (detectedRic == null && foundKeywords.isEmpty()) {
                return null
            }

            val meta = detectedRic?.let { RIC_METADATA[it] }
            OcrPackagingEvidence(
                resinCode = detectedRic,
                materialCode = meta?.first,
                materialName = meta?.second,
                coarseCategory = meta?.third,
                detectedKeywords = foundKeywords,
                confidence = if (detectedRic != null) 0.92f else 0.75f,
                rawMatchedText = matchedText.ifBlank { foundKeywords.joinToString(", ") }
            )
        } catch (e: Exception) {
            Log.w(TAG, "OCR recognition error: ${e.message}")
            null
        }
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing OCR recognizer", e)
        }
    }
}
