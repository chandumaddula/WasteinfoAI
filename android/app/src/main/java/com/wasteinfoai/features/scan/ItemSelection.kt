package com.wasteinfoai.ai.vision

import android.graphics.RectF

/** What the selection needs to know about one classified crop. */
data class ClassifiedCrop(
    val accepted: Boolean,
    val state: AcceptanceState,
    val confidence: Float,   // top-1 main-category probability
    val category: String,    // top-1 main category
    val box: RectF?          // null = the whole image was classified
)

data class Selection(val shown: List<Int>, val hiddenAsNonWaste: Int)

/**
 * Decides which classified crops the user sees.
 *
 *  - every ACCEPTED crop is shown;
 *  - a crop the model is unsure about is ALSO shown ("needs confirmation") when it still looks like waste with decent
 *    confidence. Previously every unaccepted crop was hidden as soon as one crop was accepted, so a mouse on a pile of
 *    papers vanished. Hands, clothes and furniture no longer need hiding by hand: the model has a "not_waste" class and
 *    those crops arrive as LIKELY_NON_WASTE;
 *  - the same category seen twice over the same stuff (three boxes on one pile of papers) is shown once. Only crops
 *    of the SAME category are merged, never different ones, so a mouse lying on the papers stays a separate item.
 */
object ItemSelection {
    const val SUGGEST_MIN_CONFIDENCE = 0.5f
    const val MERGE_IOU = 0.5f
    const val MERGE_IO_MIN = 0.4f

    fun select(crops: List<ClassifiedCrop>, maxItems: Int = 6, suggestMin: Float = SUGGEST_MIN_CONFIDENCE): Selection {
        if (crops.isEmpty()) return Selection(emptyList(), 0)
        val order = crops.indices.sortedWith(
            compareByDescending<Int> { crops[it].accepted }.thenByDescending { crops[it].confidence }
        )
        val candidates = order.filter { i ->
            val c = crops[i]
            c.accepted || (c.state != AcceptanceState.LIKELY_NON_WASTE &&
                c.state != AcceptanceState.INVALID_OUTPUT && c.confidence >= suggestMin)
        }
        val kept = ArrayList<Int>()
        for (i in candidates) {
            val a = crops[i]
            val ab = a.box
            val duplicate = ab != null && kept.any { j ->
                val b = crops[j]
                val bb = b.box
                bb != null && a.category.equals(b.category, ignoreCase = true) &&
                    (BoxProposals.iou(ab, bb) >= MERGE_IOU || BoxProposals.ioMin(ab, bb) >= MERGE_IO_MIN)
            }
            if (!duplicate) kept += i
            if (kept.size >= maxItems) break
        }
        // Nothing usable: show the single best crop so the screen can offer manual selection.
        if (kept.isEmpty()) return Selection(listOf(order.first()), crops.size - 1)
        return Selection(kept, crops.size - candidates.size)
    }
}
