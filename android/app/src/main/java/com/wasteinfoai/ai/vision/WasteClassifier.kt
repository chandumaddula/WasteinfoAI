package com.wasteinfoai.ai.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * On-device waste classifier (ExecuTorch).
 *
 * The exported model already outputs CALIBRATED PROBABILITIES (softmax + temperature are baked in
 * by export_model.py). This class therefore must NOT apply softmax a second time. It detects the
 * output type, so older logit-only .pte files keep working.
 *
 * Acceptance thresholds are read from vision/model_metadata.json (written by the calibration step)
 * unless a policy / threshold is injected explicitly.
 */
class WasteClassifier(
    private val context: Context,
    private val modelAssetPath: String = "vision/waste_classifier.pte",
    private val labelsAssetPath: String = "vision/class_names.json",
    private val confidenceThreshold: Float? = null,
    private val acceptancePolicy: PredictionAcceptancePolicy? = null,
    private val metadataAssetPath: String = "vision/model_metadata.json"
) : AutoCloseable {

    companion object {
        private const val TAG = "WasteClassifier"
        private val FALLBACK_LABELS = listOf(
            "Battery", "Keyboard", "Microwave", "Mobile", "Mouse",
            "PCB", "Player", "Printer", "Television", "Washing Machine",
            "cardboard", "glass", "metal", "organic", "paper",
            "plastic", "trash"
        )
    }

    @Volatile private var module: Module? = null
    @Volatile private var classLabels: List<String> = emptyList()
    @Volatile private var isInitialized = false
    @Volatile private var modelVersion = "unknown"
    @Volatile private var policy: PredictionAcceptancePolicy = acceptancePolicy ?: PredictionAcceptancePolicy(
        AcceptancePolicyConfig(
            confidenceThreshold = confidenceThreshold ?: AcceptancePolicyConfig().confidenceThreshold
        )
    )

    private val initMutex = Mutex()
    private val inferMutex = Mutex() // Module.forward is not thread-safe

    suspend fun initialize(): kotlin.Result<Unit> = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (isInitialized && module != null) return@withLock kotlin.Result.success(Unit)
            try {
                loadClassLabels()
                loadMetadata()
                val modelFile = copyAssetIfStale(modelAssetPath, "waste_classifier.pte")
                module = Module.load(modelFile.absolutePath)
                isInitialized = true
                Log.i(TAG, "ExecuTorch model loaded ($modelVersion) from ${modelFile.path}")
                kotlin.Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load ExecuTorch model", e)
                kotlin.Result.failure(e)
            }
        }
    }

    private fun loadClassLabels() {
        classLabels = try {
            val json = JSONObject(context.assets.open(labelsAssetPath).bufferedReader().use { it.readText() })
            List(json.length()) { i -> json.optString(i.toString(), "Unknown_$i") }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load labels asset, using canonical 17 classes", e)
            FALLBACK_LABELS
        }
    }

    private fun loadMetadata() {
        val meta = runCatching {
            JSONObject(context.assets.open(metadataAssetPath).bufferedReader().use { it.readText() })
        }.getOrNull()
        if (meta == null) {
            Log.w(TAG, "No $metadataAssetPath found; using default acceptance thresholds")
            return
        }
        modelVersion = "${meta.optString("version", "?")}-${meta.optString("architecture", "model")}"

        val metaClasses = meta.optJSONArray("classes")
        if (metaClasses != null && metaClasses.length() != classLabels.size) {
            Log.e(TAG, "Metadata has ${metaClasses.length()} classes but class_names.json has ${classLabels.size}")
        }
        // Explicitly injected policy / threshold wins over metadata.
        if (acceptancePolicy == null && confidenceThreshold == null) {
            policyFromMetadata(meta)?.let {
                policy = PredictionAcceptancePolicy(it)
                Log.i(TAG, "Acceptance policy loaded from metadata: $it")
            }
        }
    }

    private fun policyFromMetadata(meta: JSONObject): AcceptancePolicyConfig? {
        val p = meta.optJSONObject("rejection_policy") ?: return null
        if (!p.has("confidence_threshold")) return null
        val conf = p.getDouble("confidence_threshold").toFloat()
        return AcceptancePolicyConfig(
            version = "calibrated-${meta.optString("version", "?")}",
            confidenceThreshold = conf,
            minMarginThreshold = p.optDouble("min_margin", 0.15).toFloat(),
            maxNormalizedEntropy = p.optDouble("max_norm_entropy", 0.70).toFloat(),
            oodConfidenceFloor = minOf(0.35f, conf)
        )
    }

    /** Copies the model out of assets only when the installed app build changed (cheap + reliable). */
    private fun copyAssetIfStale(assetPath: String, outputName: String): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, outputName)
        val stampFile = File(dir, "$outputName.stamp")
        val stamp = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("0")

        if (target.exists() && target.length() > 0 && stampFile.exists() && stampFile.readText() == stamp) {
            return target
        }
        val tmp = File(dir, "$outputName.tmp")
        context.assets.open(assetPath).use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        stampFile.writeText(stamp)
        return target
    }

    private suspend fun ensureReady(): Throwable? {
        if (isInitialized && module != null) return null
        return initialize().exceptionOrNull() ?: if (module == null) IllegalStateException("Classifier not initialized") else null
    }

    suspend fun classify(
        imageFile: File,
        strategy: PreprocessingStrategy = PreprocessingStrategy.RESIZE_FULL,
        roi: RectF? = null
    ): kotlin.Result<ClassificationResult> = withContext(Dispatchers.Default) {
        ensureReady()?.let { return@withContext kotlin.Result.failure(it) }
        val start = System.currentTimeMillis()
        var bitmap: Bitmap? = null
        try {
            bitmap = ImagePreprocessor.loadAndPreprocessBitmap(imageFile, strategy, roi)
            kotlin.Result.success(infer(bitmap, start))
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed on ${imageFile.name}", e)
            kotlin.Result.failure(e)
        } finally {
            bitmap?.recycle()
        }
    }

    suspend fun classify(
        sourceBitmap: Bitmap,
        strategy: PreprocessingStrategy = PreprocessingStrategy.RESIZE_FULL,
        roi: RectF? = null
    ): kotlin.Result<ClassificationResult> = withContext(Dispatchers.Default) {
        ensureReady()?.let { return@withContext kotlin.Result.failure(it) }
        val start = System.currentTimeMillis()
        var preprocessed: Bitmap? = null
        try {
            preprocessed = ImagePreprocessor.preprocessBitmap(sourceBitmap, strategy, roi)
            kotlin.Result.success(infer(preprocessed, start))
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed on bitmap", e)
            kotlin.Result.failure(e)
        } finally {
            if (preprocessed !== sourceBitmap) preprocessed?.recycle()
        }
    }

    private suspend fun infer(preprocessed: Bitmap, startTime: Long): ClassificationResult {
        val input = ImagePreprocessor.bitmapToNormalizedFloatArray(preprocessed)
        val tensor = Tensor.fromBlob(
            input,
            longArrayOf(1, 3, ImagePreprocessor.INPUT_HEIGHT.toLong(), ImagePreprocessor.INPUT_WIDTH.toLong())
        )
        val values = inferMutex.withLock {
            val outputs = module?.forward(EValue.from(tensor))
                ?: throw IllegalStateException("Classifier not initialized")
            if (outputs.isEmpty()) throw IllegalStateException("ExecuTorch returned empty output")
            outputs[0].toTensor().dataAsFloatArray
        }
        return parseInferenceOutput(values, System.currentTimeMillis() - startTime)
    }

    /** True when the model already returned a probability distribution (softmax baked into export). */
    private fun looksLikeProbabilities(v: FloatArray): Boolean {
        var sum = 0f
        for (x in v) {
            if (x.isNaN() || x < -1e-4f || x > 1f + 1e-4f) return false
            sum += x
        }
        return abs(sum - 1f) < 2e-3f
    }

    private fun parseInferenceOutput(values: FloatArray, durationMs: Long): ClassificationResult {
        val labels = classLabels
        if (values.size != labels.size) {
            return ClassificationResult.uncertain(
                reason = "Model output size ${values.size} does not match ${labels.size} class labels.",
                durationMs = durationMs,
                state = AcceptanceState.INVALID_OUTPUT
            )
        }

        val isProb = looksLikeProbabilities(values)
        val evalResult = if (isProb) policy.evaluateProbabilities(values, labels) else policy.evaluate(values, labels)
        val probabilities = if (isProb) values else policy.computeSoftmax(values)

        val topK = probabilities.indices
            .map { ClassScore(it, labels[it], probabilities[it]) }
            .sortedByDescending { it.score }
            .take(3)

        return if (evalResult.isAccepted) {
            ClassificationResult(
                classId = evalResult.top1ClassId,
                label = evalResult.top1Class,
                confidence = evalResult.confidence,
                inferenceDurationMs = durationMs,
                modelVersion = modelVersion,
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
            ).copy(confidence = evalResult.confidence) // keep the real top-1 score so the UI does not show 0.0%
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
