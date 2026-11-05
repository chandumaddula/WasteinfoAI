package com.wasteinfoai.ai.vision

import android.graphics.RectF

/**
 * Cleans up raw detector boxes before every one of them is classified.
 * Boxes are normalized (0..1) in the SAME upright image the classifier will crop from.
 */
object BoxProposals {
    const val MIN_AREA = 0.015f  // ignore specks (< 1.5% of the picture)
    const val MAX_AREA = 0.92f   // a box covering nearly everything is the scene, not an item
    const val NMS_IOU = 0.6f

    /**
     * Returns indexes into [boxes], most salient first, after dropping tiny/huge boxes and near-duplicates.
     * An empty result means "no usable item box": the caller should classify the whole image.
     */
    fun refine(boxes: List<RectF>, scores: List<Float>, maxBoxes: Int = 6): List<Int> {
        require(boxes.size == scores.size) { "boxes and scores must have the same size" }
        val order = boxes.indices
            .filter { area(boxes[it]) in MIN_AREA..MAX_AREA }
            .sortedByDescending { scores[it] }
        val kept = ArrayList<Int>()
        for (i in order) {
            if (kept.none { iou(boxes[it], boxes[i]) > NMS_IOU }) kept += i
            if (kept.size >= maxBoxes) break
        }
        return kept
    }

    fun area(b: RectF): Float = maxOf(0f, b.right - b.left) * maxOf(0f, b.bottom - b.top)

    /** Intersection divided by the SMALLER box: 1.0 when one box lies completely inside the other. */
    fun ioMin(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bt = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, r - l) * maxOf(0f, bt - t)
        val smaller = minOf(area(a), area(b))
        return if (smaller <= 0f) 0f else inter / smaller
    }

    fun iou(a: RectF, b: RectF): Float {
        val l = maxOf(a.left, b.left)
        val t = maxOf(a.top, b.top)
        val r = minOf(a.right, b.right)
        val bt = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, r - l) * maxOf(0f, bt - t)
        val union = area(a) + area(b) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
