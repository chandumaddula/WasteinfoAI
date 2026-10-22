package com.wasteinfoai.ai.barcode

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device ML Kit barcode scanner designed for waste item packaging identification.
 * Operates offline without external network dependencies.
 */
class BarcodeScannerService(
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128
            )
            .build()
    )
) {
    companion object {
        private const val TAG = "BarcodeScannerService"
    }

    /**
     * Attempts to detect and decode a barcode from a static bitmap.
     * Returns the first valid product barcode or null if none detected.
     */
    suspend fun scanBarcode(bitmap: Bitmap): String? = suspendCancellableCoroutine { continuation ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    // Only numeric GTINs (8-14 digits). The old fallback returned arbitrary QR/Code128 text,
                    // which was then pasted into the product-lookup URL.
                    val validBarcode = barcodes
                        .mapNotNull { it.rawValue?.trim() }
                        .firstOrNull { it.length in 8..14 && it.all { c -> c.isDigit() } }

                    if (continuation.isActive) continuation.resume(validBarcode)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Barcode detection failed: ${e.message}")
                    continuation.resume(null)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing barcode image", e)
            continuation.resume(null)
        }
    }

    fun close() {
        try {
            scanner.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing barcode scanner", e)
        }
    }
}
