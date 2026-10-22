package com.wasteinfoai.ai.barcode

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max

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
 * On-device OCR (ML Kit Latin) for Resin Identification Codes and disposal instructions.
 *
 * NOTE: [scanPackagingText] blocks the calling thread (up to [timeoutMs]); call it from Dispatchers.IO/Default.
 * Parsing lives in [extractEvidence] (pure function, JVM unit-testable).
 */
class PackagingOcrService(
    private val recognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
    private val timeoutMs: Long = 2500L
) : AutoCloseable {

    companion object {
        private const val TAG = "PackagingOcrService"
        private const val MAX_OCR_SIDE = 1280 // full-res photos only slow OCR down

        private val UNICODE_RIC = listOf('♳' to 1, '♴' to 2, '♵' to 3, '♶' to 4, '♷' to 5, '♸' to 6, '♹' to 7)

        // "PET 1", "HDPE-2", "PP 5": the digit MUST agree with the resin name (rejects "PP 3", "PS 1").
        private val NAME_WITH_DIGIT = Regex("""\b(PETE?|HDPE|PVC|LDPE|PP|PS|OTHER)\s*[-:]?\s*0?([1-7])\b""")
        // Letters printed under the triangle: ">PET<", ">HDPE<"
        private val NAME_IN_TRIANGLE = Regex(""">\s*(PETE?|HDPE|PVC|LDPE|PP|PS|OTHER)\s*<""")
        // Names that are never ordinary English words. "PET" (pet food), "OTHER", "PP", "PS" are NOT accepted alone.
        private val UNAMBIGUOUS_NAME = Regex(
            """\b(HDPE|LDPE|PETE|POLYPROPYLENE|POLYSTYRENE|POLYETHYLENE TEREPHTHALATE|POLYVINYL CHLORIDE)\b"""
        )
        private val CONTEXT_DIGIT = Regex(
            """\b(?:RIC|RESIN(?: CODE)?|RECYCLING CODE|PLASTIC)\s*(?:#|NO\.?|NUMBER)?\s*([1-7])\b"""
        )

        private val RESIN_CODE_BY_NAME = mapOf(
            "PET" to 1, "PETE" to 1, "POLYETHYLENE TEREPHTHALATE" to 1,
            "HDPE" to 2,
            "PVC" to 3, "POLYVINYL CHLORIDE" to 3,
            "LDPE" to 4,
            "PP" to 5, "POLYPROPYLENE" to 5,
            "PS" to 6, "POLYSTYRENE" to 6,
            "OTHER" to 7
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

        /** Pure text -> evidence parser. */
        fun extractEvidence(rawText: String): OcrPackagingEvidence? {
            val text = rawText.uppercase()
            if (text.isBlank()) return null

            var ric: Int? = null
            var matched = ""
            var confidence = 0.92f

            // 1) Unicode recycling symbols
            for ((symbol, code) in UNICODE_RIC) {
                if (text.contains(symbol)) { ric = code; matched = symbol.toString(); break }
            }
            // 2) Name + matching digit ("PET 1")
            if (ric == null) {
                for (m in NAME_WITH_DIGIT.findAll(text)) {
                    val digit = m.groupValues[2].toInt()
                    if (RESIN_CODE_BY_NAME[m.groupValues[1]] == digit) { ric = digit; matched = m.value; break }
                }
            }
            // 3) ">PET<" under the triangle
            if (ric == null) {
                NAME_IN_TRIANGLE.find(text)?.let { m ->
                    ric = RESIN_CODE_BY_NAME[m.groupValues[1]]; matched = m.value
                }
            }
            // 4) Digit with explicit recycling context ("RIC 5", "RESIN CODE 2")
            if (ric == null) {
                CONTEXT_DIGIT.find(text)?.let { m -> ric = m.groupValues[1].toInt(); matched = m.value }
            }
            // 5) Unambiguous material names (lower confidence: may describe a component, not the body)
            if (ric == null) {
                UNAMBIGUOUS_NAME.find(text)?.let { m ->
                    ric = RESIN_CODE_BY_NAME[m.groupValues[1]]; matched = m.value; confidence = 0.85f
                }
            }

            val keywords = INSTRUCTION_KEYWORDS.filter { text.contains(it) }
            if (ric == null && keywords.isEmpty()) return null

            val meta = ric?.let { RIC_METADATA[it] }
            return OcrPackagingEvidence(
                resinCode = ric,
                materialCode = meta?.first,
                materialName = meta?.second,
                coarseCategory = meta?.third,
                detectedKeywords = keywords,
                confidence = if (ric != null) confidence else 0.75f,
                rawMatchedText = matched.ifBlank { keywords.joinToString(", ") }
            )
        }
    }

    fun scanPackagingText(bitmap: Bitmap): OcrPackagingEvidence? {
        var work: Bitmap? = null
        return try {
            work = downscaleForOcr(bitmap)
            val task = recognizer.process(InputImage.fromBitmap(work, 0))
            val result = Tasks.await(task, timeoutMs, TimeUnit.MILLISECONDS)
            extractEvidence(result.text)
        } catch (e: Exception) {
            Log.w(TAG, "OCR recognition error: ${e.message}")
            null
        } finally {
            val w = work
            if (w != null && w !== bitmap) w.recycle()
        }
    }

    private fun downscaleForOcr(bitmap: Bitmap): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= MAX_OCR_SIDE) return bitmap
        val s = MAX_OCR_SIDE / longSide.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * s).toInt().coerceAtLeast(1), (bitmap.height * s).toInt().coerceAtLeast(1), true
        )
    }

    override fun close() {
        try {
            recognizer.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing OCR recognizer", e)
        }
    }
}
