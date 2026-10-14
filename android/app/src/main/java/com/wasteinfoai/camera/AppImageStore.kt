package com.wasteinfoai.camera

import android.content.Context
import android.net.Uri
import com.wasteinfoai.core.util.FileNaming
import com.wasteinfoai.core.util.SizeLimitExceededException
import com.wasteinfoai.core.util.copyWithLimit
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores images in `filesDir/captures` (app-private; needs no storage permission). File names are
 * generated, never derived from user or URI data. Gallery images are COPIED immediately so nothing
 * depends on a content URI whose access may expire, and no persistable URI permission is taken.
 */
class AppImageStore(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageStore {

    private val appContext = context.applicationContext
    private val directory: File get() = File(appContext.filesDir, DIRECTORY)

    override fun createCaptureFile(): File = File(ensureDirectory(), FileNaming.newImageFileName("capture", "jpg"))

    override suspend fun importFromUri(uri: String): File = withContext(ioDispatcher) {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != "content") throw ImageImportException(ImageProblem.IMPORT_FAILED) // no file:// or http
        val destination = File(ensureDirectory(), FileNaming.newImageFileName("gallery", "img"))
        try {
            val input = appContext.contentResolver.openInputStream(parsed)
                ?: throw ImageImportException(ImageProblem.IMPORT_FAILED)
            input.use { source -> // both streams are closed even on failure: no leaked descriptors
                destination.outputStream().use { target -> copyWithLimit(source, target, MAX_IMPORT_BYTES) }
            }
            destination
        } catch (e: CancellationException) {
            destination.delete()
            throw e
        } catch (e: SizeLimitExceededException) {
            destination.delete()
            throw ImageImportException(ImageProblem.TOO_LARGE, e)
        } catch (e: ImageImportException) {
            destination.delete()
            throw e
        } catch (e: IOException) { // includes FileNotFoundException
            destination.delete()
            throw ImageImportException(ImageProblem.IMPORT_FAILED, e)
        } catch (e: RuntimeException) { // SecurityException, provider-specific failures
            destination.delete()
            throw ImageImportException(ImageProblem.IMPORT_FAILED, e)
        }
    }

    /** Deletes only files inside the capture directory. */
    override fun delete(file: File) {
        try {
            if (file.parentFile?.canonicalFile == directory.canonicalFile) file.delete()
        } catch (_: IOException) {
            // Could not resolve the path; leave the file alone rather than risk deleting elsewhere.
        }
    }

    private fun ensureDirectory(): File {
        val dir = directory
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create image directory")
        return dir
    }

    companion object {
        private const val DIRECTORY = "captures"
        const val MAX_IMPORT_BYTES = 40L * 1024 * 1024
    }
}
