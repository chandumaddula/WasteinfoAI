package com.wasteinfoai.core.util

import java.util.UUID

/** Generates file names from fixed, validated parts only. User-supplied text is never used in a path. */
object FileNaming {
    private val SAFE_PART = Regex("^[a-z0-9]{1,16}$")

    fun newImageFileName(
        prefix: String,
        extension: String,
        nowMillis: Long = System.currentTimeMillis(),
        randomSuffix: String = UUID.randomUUID().toString().replace("-", "").take(8),
    ): String {
        require(SAFE_PART.matches(prefix)) { "Unsafe file prefix" }
        require(SAFE_PART.matches(extension)) { "Unsafe file extension" }
        require(SAFE_PART.matches(randomSuffix.lowercase().take(16)) && randomSuffix.all { it.isLetterOrDigit() }) {
            "Unsafe random suffix"
        }
        return "${prefix}_${nowMillis}_${randomSuffix.lowercase()}.$extension"
    }
}
