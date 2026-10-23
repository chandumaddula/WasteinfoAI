package com.wasteinfoai.ai.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** One detected piece of waste: a normalized (0..1) box plus the classifier verdict for that crop. */
data class DetectedWasteItem(
    val box: RectF,
    val result: ClassificationResult,
    val proposalSource: String
)

/**
 * Finds several items in one photo and classifies EACH crop separately.
 *
 * A 17-class image classifier can only give one label per image, so a desk with a plastic bag,
 * earphones, a blister pack and paper always comes out as a single (usually wrong or low-confidence) answer.
 *
 * Pipeline: box proposals -> NMS -> classify every crop -> keep crops the acceptance policy trusts.
 * Proposals come from ML Kit object detection (on-device, multi-object), your OpenCV ROIs
 * ([extraProposals]) and, when few boxes are found, overlapping tiles as a fallback.
 *
 * Needs: implementation("com.google.mlkit:object-detection:17.0.2")
 * [bitmap] must already be upright (EXIF applied), otherwise boxes land on the wrong region.
 */
class MultiItemAnalyzer(
    private val classifier: WasteClassifier,
    private val maxProposals: Int = 8,
    private val maxItems: Int = 6
) : AutoCloseable {

    companion object {
        private const val TAG = "MultiItemAnalyzer"
        private const val MIN_AREA = 0.01f   // ignore specks (< 1% of the image)
        private const val MAX_AREA = 0.92f   // near-whole-image boxes only if nothing smaller exists
    }

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .build()
    )

    suspend fun analyze(bitmap: Bitmap, extraProposals: List<RectF> = emptyList()): List<DetectedWasteItem> =
        withContext(Dispatchers.Default) {
            val proposals = ArrayList<Pair<RectF, String>>()
            mlKitBoxes(bitmap).forEach { proposals += it to "mlkit" }
            extraProposals.forEach { proposals += it to "opencv" }
            if (proposals.size < 2) tiles().forEach { proposals += it to "tile" }

            val usable = proposals
                .filter { (b, _) -> area(b) in MIN_AREA..MAX_AREA }
                .ifEmpty { listOf(RectF(0f, 0f, 1f, 1f) to "full") }
            val deduped = nms(usable, { it.first }, { 0f }, iouThreshold = 0.6f).take(maxProposals)

            val items = deduped.mapNotNull { (box, source) ->
                classifier.classify(bitmap, PreprocessingStrategy.ROI_CROP, box)
                    .getOrNull()?.let { DetectedWasteItem(box, it, source) }
            }

            // Same class overlapping a lot -> one item. Different class needs near-identical boxes to be merged.
            val kept = ArrayList<DetectedWasteItem>()
            for (item in items.sortedByDescending { it.result.confidence }) {
                val dup = kept.any {
                    val iou = iou(it.box, item.box)
                    iou > 0.7f || (iou > 0.4f && it.result.label == item.result.label)
                }
                if (!dup) kept += item
            }
            kept.sortedWith(compareByDescending<DetectedWasteItem> { it.result.isAccepted }
                .thenByDescending { it.result.confidence })
                .take(maxItems)
        }

    private fun mlKitBoxes(bitmap: Bitmap): List<RectF> = try {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)), 4, TimeUnit.SECONDS).map { o ->
            val r = o.boundingBox
            RectF(
                (r.left / w).coerceIn(0f, 1f), (r.top / h).coerceIn(0f, 1f),
                (r.right / w).coerceIn(0f, 1f), (r.bottom / h).coerceIn(0f, 1f)
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "ML Kit object detection failed: ${e.message}")
        emptyList()
    }

    private fun tiles() = listOf(
        RectF(0f, 0f, 0.6f, 0.6f), RectF(0.4f, 0f, 1f, 0.6f),
        RectF(0f, 0.4f, 0.6f, 1f), RectF(0.4f, 0.4f, 1f, 1f), RectF(0.2f, 0.2f, 0.8f, 0.8f)
    )

    private fun area(b: RectF) = b.width() * b.height()

    private fun iou(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left); val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right); val bt = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, r - l) * maxOf(0f, bt - t)
        val union = area(a) + area(b) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun <T> nms(items: List<T>, box: (T) -> RectF, score: (T) -> Float, iouThreshold: Float): List<T> {
        val out = ArrayList<T>()
        for (it in items.sortedByDescending(score)) {
            if (out.none { o -> iou(box(o), box(it)) > iouThreshold }) out += it
        }
        return out
    }

    override fun close() {
        runCatching { detector.close() }
    }
}
