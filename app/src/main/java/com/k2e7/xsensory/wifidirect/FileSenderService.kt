package com.k2e7.xsensory.wifidirect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class FileSenderService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Preparing transfer…"))

        val hostAddress = intent?.getStringExtra(EXTRA_HOST) ?: run {
            Log.e(TAG, "No host address provided")
            stopSelf(); return START_NOT_STICKY
        }
        val fileUri = intent.getStringExtra(EXTRA_URI)?.let { Uri.parse(it) } ?: run {
            Log.e(TAG, "No file URI provided")
            stopSelf(); return START_NOT_STICKY
        }
        val callback = progressCallback ?: run {
            Log.e(TAG, "No callback set")
            stopSelf(); return START_NOT_STICKY
        }

        Thread { sendFile(hostAddress, fileUri, callback) }.start()

        return START_NOT_STICKY
    }

    // -------------------------------------------------------------------------
    // Core transfer logic
    // -------------------------------------------------------------------------

    private fun sendFile(host: String, uri: Uri, cb: TransferProgressCallback) {
        val cr = contentResolver

        // Resolve file metadata from the content resolver
        var fileName = "file"
        var fileSize = -1L
        var mimeType = "application/octet-stream"

        cr.query(uri, null, null, null, null)?.use { cursor ->
            val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameCol >= 0) fileName = cursor.getString(nameCol) ?: fileName
                if (sizeCol >= 0) fileSize = cursor.getLong(sizeCol)
            }
        }
        mimeType = cr.getType(uri) ?: mimeType

        val meta = TransferMetadata(fileName, fileSize, mimeType)
        Log.d(TAG, "Sending: $meta to $host:$PORT")

        // ── Retry loop ───────────────────────────────────────────────────────
        // The receiver may not have tapped Receive yet, so their ServerSocket
        // might not be open when we first try. We retry every RETRY_INTERVAL_MS
        // for up to RETRY_TOTAL_MS before giving up.
        val deadline = System.currentTimeMillis() + RETRY_TOTAL_MS
        var attempt = 0
        var lastError: Exception? = null

        while (System.currentTimeMillis() < deadline) {
            attempt++
            Log.d(TAG, "Connect attempt $attempt to $host:$PORT")
            updateNotification(
                if (attempt == 1) "Connecting…"
                else "Waiting for receiver… (attempt $attempt)"
            )

            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, PORT), CONNECT_ATTEMPT_TIMEOUT_MS)
                socket.soTimeout = SO_TIMEOUT_MS

                // Connected — stream the file
                socket.use {
                    val out = BufferedOutputStream(socket.getOutputStream())

                    // 1. Write the metadata header line
                    out.write(meta.toHeader().toByteArray(Charsets.UTF_8))

                    // 2. Stream file bytes with progress callbacks
                    cr.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var sent = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            sent += read
                            cb.onProgress(sent, fileSize)
                            updateNotification("Sending… ${progressPercent(sent, fileSize)}%")
                        }
                    }

                    out.flush()
                }

                Log.d(TAG, "Send complete after $attempt attempt(s)")
                cb.onComplete(null)
                updateNotification("Transfer complete")
                stopSelf()
                return  // success — exit the retry loop

            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Attempt $attempt failed: ${e.message}")

                // Wait before retrying, but don't sleep past the deadline
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) Thread.sleep(minOf(RETRY_INTERVAL_MS, remaining))
            }
        }

        // All attempts exhausted
        Log.e(TAG, "Send failed after $attempt attempt(s)", lastError)
        cb.onError("Could not connect after $attempt attempts — make sure the receiver tapped Receive first")
        stopSelf()
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("XSensory — Sending file")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "File Transfer", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG                    = "FileSenderService"
        const val PORT                           = 8888
        private const val NOTIF_ID               = 1001
        private const val CHANNEL_ID             = "xsensory_transfer"
        private const val BUFFER_SIZE            = 65_536

        // Each individual connect() call times out after 3 s so we don't
        // block the whole retry budget on a single slow attempt.
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 3_000

        // Keep retrying for up to 30 seconds total — plenty of time for the
        // receiver to tap their button.
        private const val RETRY_TOTAL_MS         = 30_000L

        // Pause between attempts
        private const val RETRY_INTERVAL_MS      = 2_000L

        private const val SO_TIMEOUT_MS          = 30_000

        private const val EXTRA_HOST = "extra_host"
        private const val EXTRA_URI  = "extra_uri"

        var progressCallback: TransferProgressCallback? = null

        fun buildIntent(context: Context, hostAddress: String, fileUri: Uri): Intent =
            Intent(context, FileSenderService::class.java).apply {
                putExtra(EXTRA_HOST, hostAddress)
                putExtra(EXTRA_URI, fileUri.toString())
            }

        private fun progressPercent(done: Long, total: Long) =
            if (total > 0) (done * 100 / total).toInt() else 0
    }
}
