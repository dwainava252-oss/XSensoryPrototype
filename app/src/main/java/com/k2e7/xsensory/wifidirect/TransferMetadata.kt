package com.k2e7.xsensory.wifidirect

/**
 * Sent as a plain-text header line before the raw file bytes.
 * Format:  "XFER:<fileName>:<fileSize>:<mimeType>\n"
 *
 * Both sender and receiver parse this so the receiver knows
 * how many bytes to read and what to name the saved file.
 */
data class TransferMetadata(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String
) {
    /** Serialise to the wire header format. */
    fun toHeader(): String = "XFER:$fileName:$fileSize:$mimeType\n"

    companion object {
        private const val PREFIX = "XFER:"

        /** Parse a header line; returns null if malformed. */
        fun fromHeader(line: String): TransferMetadata? {
            if (!line.startsWith(PREFIX)) return null
            val parts = line.removePrefix(PREFIX).split(":")
            if (parts.size < 3) return null
            return runCatching {
                TransferMetadata(
                    fileName = parts[0],
                    fileSize = parts[1].toLong(),
                    mimeType = parts[2]
                )
            }.getOrNull()
        }
    }
}
