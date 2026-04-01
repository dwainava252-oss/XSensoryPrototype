package com.k2e7.xsensory.wifidirect

/**
 * Callbacks fired on a background thread — always post to the main thread
 * before touching any UI (e.g. runOnUiThread { ... }).
 */
interface TransferProgressCallback {
    /** @param bytesDone bytes transferred so far, @param total file size in bytes */
    fun onProgress(bytesDone: Long, total: Long)

    /** Transfer finished successfully.
     *  @param fileUri  content:// URI of the saved file (receiver only; sender passes null) */
    fun onComplete(fileUri: android.net.Uri?)

    /** Something went wrong. */
    fun onError(message: String)
}
