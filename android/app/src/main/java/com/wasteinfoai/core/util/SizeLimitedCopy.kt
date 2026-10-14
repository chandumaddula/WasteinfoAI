package com.wasteinfoai.core.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SizeLimitExceededException(val maxBytes: Long) : IOException("Input exceeds $maxBytes bytes")

/**
 * Copies [input] to [output], failing as soon as more than [maxBytes] have been read, so an
 * oversized or endless source can never fill app storage. Does not close either stream.
 */
fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {
    require(maxBytes > 0) { "maxBytes must be positive" }
    val buffer = ByteArray(DEFAULT_BUFFER)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw SizeLimitExceededException(maxBytes)
        output.write(buffer, 0, read)
    }
    return total
}

private const val DEFAULT_BUFFER = 16 * 1024
