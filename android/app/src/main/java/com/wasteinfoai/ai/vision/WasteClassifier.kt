package com.wasteinfoai.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.exp

import android.graphics.RectF

/**
 * On-device waste image classifier powered by ExecuTorch runtime and trained ReXNet-150.
 */
class WasteClassifier(
    private val context: Context,
    private val modelAssetPath: String = "vision/waste_classifier.pte",
    private val labelsAssetPath: String = "vision/class_names.json",
    private val confidenceThreshold: Float = 0.62f,
    private val acceptancePolicy: PredictionAcceptancePolicy = PredictionAcceptancePolicy(
        AcceptancePolicyConfig(confidenceThreshold = confidenceThreshold)
    )
) : AutoCloseable {

    companion object {
        private const val TAG = "WasteClassifier"
        private const val NUM_CLASSES = 17
    }

    private var module: Module? = null
    private var classLabels: List<String> = emptyList()
    private var isInitialized = false

    /**
     * Initializes the ExecuTorch runtime module and class labels on a background dispatcher.
     */
    suspend fun initialize(): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        if (isInitialized && module != null) {
            return@withContext kotlin.Result.success(Unit)
        }

        try {
            Log.i(TAG, "Initializing ExecuTorch classifier...")
            loadClassLabels()
            val modelFile = copyAssetToFileIfNeeded(modelAssetPath, "waste_classifier.pte")
            module = Module.load(modelFile.absolutePath)
            isInitialized = true
            Log.i(TAG, "ExecuTorch model loaded successfully from ${modelFile.path}")
            kotlin.Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ExecuTorch model", e)
            kotlin.Result.failure(e)
        }
    }

    private fun loadClassLabels() {
        try {
            val jsonString = context.assets.open(labelsAssetPath).bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val labels = mutableListOf<String>()
            for (i in 0 until json.length()) {
                labels.add(json.optString(i.toString(), "Unknown_$i"))
            }
            classLabels = labels
            Log.i(TAG, "Loaded ${classLabels.size} verified class labels")
        } catch (e: Exception) {
            Log.w(TAG, "Could not load labels from asset, falling back to canonical 17 classes", e)
            classLabels = listOf(
                "Battery", "Keyboard", "Microwave", "Mobile", "Mouse",
                "PCB", "Player", "Printer", "Television", "Washing Machine",
                "cardboard", "glass", "metal", "organic", "paper",
                "plastic", "trash"
            )
        }
    }

    private fun copyAssetToFileIfNeeded(assetPath: String, outputFileName: String): File {
        val targetDir = File(context.filesDir, "models").apply { mkdirs() }
        val targetFile = File(targetDir, outputFileName)

        // Read asset size and copy if not present or changed
        context.assets.open(assetPath).use { input ->
            val assetSize = input.available()
            if (targetFile.exists() && targetFile.length() == assetSize.toLong()) {
                return targetFile
            }
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return targetFile
    }

    /**
     * Runs on-device inference on [imageFile] using [strategy] and optional [roi].
     */
    suspend fun classify(
        imageFile: File,
        strategy: PreprocessingStrategy = PreprocessingStrategy.LETTERBOX,
        roi: RectF? = null
    ): kotlin.Result<ClassificationResult> = withContext(Dispatchers.Default) {
        if (!isInitialized || module == null) {
            val initResult = initialize()
            if (initResult.isFailure) {
                return@withContext kotlin.Result.failure(
                    initResult.exceptionOrNull() ?: IllegalStateException("Classifier not initialized")
                )
            }
        }

        val startTime = System.currentTimeMillis()
        var bitmap: Bitmap? = null
        try {
            bitmap = ImagePreprocessor.loadAndPreprocessBitmap(imageFile, strategy, roi)
            val inputFloatArray = ImagePreprocessor.bitmapToNormalizedFloatArray(bitmap)

            // Shape: [1, 3, 224, 224]
            val inputTensor = Tensor.fromBlob(
                inputFloatArray,
                longArrayOf(1, 3, ImagePreprocessor.INPUT_WIDTH.toLong(), ImagePreprocessor.INPUT_HEIGHT.toLong())
            )
            val inputEValue = EValue.from(inputTensor)

            val outputs = module!!.forward(inputEValue)
            if (outputs.isEmpty()) {
                return@withContext kotlin.Result.failure(IllegalStateException("ExecuTorch returned empty output"))
            }

            val outputTensor = outputs[0].toTensor()
            val rawLogits = outputTensor.dataAsFloatArray
            val durationMs = System.currentTimeMillis() - startTime

            val result = parseInferenceOutput(rawLogits, durationMs)
            kotlin.Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Inference execution failed on ${imageFile.name}", e)
            kotlin.Result.failure(e)
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Runs on-device inference on an in-memory [sourceBitmap] using [strategy] and optional [roi].
     */
    suspend fun classify(
        sourceBitmap: Bitmap,
        strategy: PreprocessingStrategy = PreprocessingStrategy.LETTERBOX,
        roi: RectF? = null
    ): kotlin.Result<ClassificationResult> = withContext(Dispatchers.Default) {
        if (!isInitialized || module == null) {
            val initResult = initialize()
            if (initResult.isFailure) {
                return@withContext kotlin.Result.failure(
                    initResult.exceptionOrNull() ?: IllegalStateException("Classifier not initialized")
                )
            }
        }

        val startTime = System.currentTimeMillis()
        var preprocessed: Bitmap? = null
        try {
            preprocessed = ImagePreprocessor.preprocessBitmap(sourceBitmap, strategy, roi)
            val inputFloatArray = ImagePreprocessor.bitmapToNormalizedFloatArray(preprocessed)

            val inputTensor = Tensor.fromBlob(
                inputFloatArray,
                longArrayOf(1, 3, ImagePreprocessor.INPUT_WIDTH.toLong(), ImagePreprocessor.INPUT_HEIGHT.toLong())
            )
            val inputEValue = EValue.from(inputTensor)

            val outputs = module!!.forward(inputEValue)
            if (outputs.isEmpty()) {
                return@withContext kotlin.Result.failure(IllegalStateException("ExecuTorch returned empty output"))
            }

            val outputTensor = outputs[0].toTensor()
            val rawLogits = outputTensor.dataAsFloatArray
            val durationMs = System.currentTimeMillis() - startTime

            val result = parseInferenceOutput(rawLogits, durationMs)
            kotlin.Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Inference execution failed on bitmap", e)
            kotlin.Result.failure(e)
        } finally {
            if (preprocessed !== sourceBitmap) {
                preprocessed?.recycle()
            }
        }
    }

    private fun parseInferenceOutput(logits: FloatArray, durationMs: Long): ClassificationResult {
        require(logits.isNotEmpty()) { "Output logits cannot be empty" }

        val evalResult = acceptancePolicy.evaluate(logits, classLabels)

        // Compute softmax for top-K extraction
        val probabilities = acceptancePolicy.computeSoftmax(logits)
        val ranked = probabilities.indices
            .map { idx ->
                val label = if (idx < classLabels.size) classLabels[idx] else "Class_$idx"
                ClassScore(idx, label, probabilities[idx])
            }
            .sortedByDescending { it.score }

        val topK = ranked.take(3)

        return if (evalResult.isAccepted) {
            ClassificationResult(
                classId = evalResult.top1ClassId,
                label = evalResult.top1Class,
                confidence = evalResult.confidence,
                inferenceDurationMs = durationMs,
                modelVersion = "1.0.0-rexnet150",
                isUncertain = false,
                topKResults = topK,
                acceptanceState = AcceptanceState.ACCEPTED,
                policyResult = evalResult
            )
        } else {
            ClassificationResult.uncertain(
                reason = "${evalResult.userFacingMessage} (${evalResult.diagnosticDetails})",
                durationMs = durationMs,
                topK = topK,
                state = evalResult.state,
                policyResult = evalResult
            )
        }
    }

    override fun close() {
        try {
            module?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying ExecuTorch native module", e)
        } finally {
            module = null
            isInitialized = false
        }
    }
}
