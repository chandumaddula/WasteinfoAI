package com.wasteinfoai.ai.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.util.Log
import com.wasteinfoai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data class Checking(val message: String) : ModelDownloadState
    data class Downloading(
        val progressPercent: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val currentFile: String
    ) : ModelDownloadState
    data object Verifying : ModelDownloadState
    data object Ready : ModelDownloadState
    data class Error(val message: String) : ModelDownloadState
}

class LlmModelManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "LlmModelManager"
        private const val MODEL_DIR = "llm"
        const val MODEL_FILE_NAME = "llama.pte"
        const val TOKENIZER_FILE_NAME = "tokenizer.bin"
        private const val MIN_FREE_BYTES = 1_000_000_000L // 1 GB required free storage
    }

    private val _downloadState = MutableStateFlow<ModelDownloadState>(
        if (isModelDownloaded()) ModelDownloadState.Ready else ModelDownloadState.Idle
    )
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    @Volatile private var isCancelled = false

    fun getModelDirectory(): File {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isModelDownloaded(): Boolean {
        val dir = File(context.filesDir, MODEL_DIR)
        val model = File(dir, MODEL_FILE_NAME)
        val tokenizer = File(dir, TOKENIZER_FILE_NAME)
        return model.exists() && model.length() > 0 && tokenizer.exists() && tokenizer.length() > 0
    }

    fun deleteModel(): Boolean {
        val dir = File(context.filesDir, MODEL_DIR)
        val model = File(dir, MODEL_FILE_NAME)
        val tokenizer = File(dir, TOKENIZER_FILE_NAME)
        var ok = true
        if (model.exists()) ok = ok && model.delete()
        if (tokenizer.exists()) ok = ok && tokenizer.delete()
        _downloadState.value = ModelDownloadState.Idle
        return ok
    }

    fun cancelDownload() {
        isCancelled = true
        _downloadState.value = ModelDownloadState.Idle
    }

    suspend fun downloadModel(
        modelUrl: String = BuildConfig.LLM_MODEL_DOWNLOAD_URL,
        tokenizerUrl: String = BuildConfig.LLM_TOKENIZER_DOWNLOAD_URL,
        requireWifi: Boolean = true,
        expectedSha256: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        isCancelled = false

        // 1. Check network constraints
        if (requireWifi && !isWifiConnected()) {
            val err = "Download requires Wi-Fi connection to prevent cellular data charges."
            _downloadState.value = ModelDownloadState.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        // 2. Check storage space
        _downloadState.value = ModelDownloadState.Checking("Checking available device storage...")
        val freeBytes = getAvailableStorageBytes()
        if (freeBytes < MIN_FREE_BYTES) {
            val err = "Insufficient storage: ~1 GB required, but only ${freeBytes / (1024 * 1024)} MB available."
            _downloadState.value = ModelDownloadState.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        val dir = getModelDirectory()
        val targetModel = File(dir, MODEL_FILE_NAME)
        val targetTokenizer = File(dir, TOKENIZER_FILE_NAME)

        try {
            // Download tokenizer first (small file, few KB/MB)
            if (!targetTokenizer.exists() || targetTokenizer.length() == 0L) {
                downloadFileWithResume(tokenizerUrl, targetTokenizer, "tokenizer.bin", 0, 1)
            }

            // Download model (.pte file ~650 MB)
            downloadFileWithResume(modelUrl, targetModel, "llama.pte", 1, 1)

            if (isCancelled) {
                return@withContext Result.failure(IllegalStateException("Download cancelled"))
            }

            // 3. Optional SHA-256 Checksum verification
            if (!expectedSha256.isNullOrBlank()) {
                _downloadState.value = ModelDownloadState.Verifying
                val hash = computeSha256(targetModel)
                if (!hash.equals(expectedSha256, ignoreCase = true)) {
                    targetModel.delete()
                    val err = "Checksum verification failed: expected $expectedSha256 but got $hash"
                    _downloadState.value = ModelDownloadState.Error(err)
                    return@withContext Result.failure(IllegalStateException(err))
                }
            }

            _downloadState.value = ModelDownloadState.Ready
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _downloadState.value = ModelDownloadState.Error(e.message ?: "Download failed")
            Result.failure(e)
        }
    }

    private fun downloadFileWithResume(
        url: String,
        destination: File,
        displayName: String,
        fileIndex: Int,
        totalFiles: Int
    ) {
        val existingBytes = if (destination.exists()) destination.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val body = response.body ?: throw IllegalStateException("Empty response body from $url")

        val isResume = response.code == 206
        val totalBytes = if (isResume) {
            existingBytes + body.contentLength()
        } else {
            body.contentLength()
        }

        val append = isResume && existingBytes > 0
        val fos = FileOutputStream(destination, append)
        val inputStream: InputStream = body.byteStream()

        var downloaded = if (append) existingBytes else 0L
        val buffer = ByteArray(16 * 1024)
        var bytesRead: Int

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled) {
                    throw IllegalStateException("Download cancelled by user")
                }
                fos.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else 0
                _downloadState.value = ModelDownloadState.Downloading(
                    progressPercent = percent,
                    bytesDownloaded = downloaded,
                    totalBytes = totalBytes,
                    currentFile = displayName
                )
            }
            fos.flush()
        } finally {
            fos.close()
            inputStream.close()
            body.close()
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun getAvailableStorageBytes(): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            stat.availableBytes
        } catch (e: Exception) {
            500_000_000L
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

