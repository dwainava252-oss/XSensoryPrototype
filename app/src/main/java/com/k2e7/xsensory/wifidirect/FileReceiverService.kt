package com.k2e7.xsensory.wifidirect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.net.ServerSocket

class FileReceiverService : Service() {

    // Held at instance level so we can close it if the service is restarted
    // before a transfer completes, preventing EADDRINUSE on the second tap.
    private var serverSocket: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ── Fix for EADDRINUSE ──────────────────────────────────────────────
        // If the user taps Receive while a previous ServerSocket is still open
        // (e.g. waiting for a connection that never came), close it first so
        // the port is free before we bind again.
        closeServerSocket()

        startForeground(NOTIF_ONGOING_ID, buildOngoingNotification("Waiting for incoming file…"))

        val callback = progressCallback ?: run {
            Log.e(TAG, "No callback set")
            stopSelf(); return START_NOT_STICKY
        }

        Thread { receiveFile(callback) }.start()

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        closeServerSocket()
    }

    // -------------------------------------------------------------------------
    // Core transfer logic
    // -------------------------------------------------------------------------

    private fun receiveFile(cb: TransferProgressCallback) {
        try {
            val ss = ServerSocket(FileSenderService.PORT).also { serverSocket = it }
            Log.d(TAG, "Listening on port ${FileSenderService.PORT}")

            ss.use { server ->
                server.accept().use { socket ->
                    Log.d(TAG, "Client connected: ${socket.inetAddress}")
                    socket.soTimeout = SO_TIMEOUT_MS

                    val buffered = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)

                    // 1. Read the metadata header
                    val headerLine = readHeaderLine(buffered)
                    val meta = TransferMetadata.fromHeader(headerLine) ?: run {
                        cb.onError("Malformed header: $headerLine")
                        return
                    }
                    Log.d(TAG, "Receiving: $meta")
                    updateOngoingNotification("Receiving ${meta.fileName}…")

                    // 2. Open a MediaStore output stream
                    val (outputUri, outputStream) = openOutputFile(meta) ?: run {
                        cb.onError("Cannot create output file for ${meta.fileName}")
                        return
                    }

                    // 3. Stream bytes with progress
                    outputStream.use { out ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var received = 0L
                        var read: Int
                        while (buffered.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            received += read
                            cb.onProgress(received, meta.fileSize)
                            updateOngoingNotification(
                                "Receiving ${meta.fileName} – ${progressPercent(received, meta.fileSize)}%"
                            )
                        }
                    }

                    // 4. Mark the file as no longer pending (Android 10+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        contentResolver.update(
                            outputUri,
                            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                            null, null
                        )
                    }

                    Log.d(TAG, "Receive complete: $outputUri")
                    cb.onComplete(outputUri)

                    // 5. Fire the completion notification (sound + tap-to-open)
                    fireCompletionNotification(meta.fileName, meta.mimeType, outputUri)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Receive failed", e)
            cb.onError(e.message ?: "Unknown error")
        } finally {
            serverSocket = null
            stopSelf()
        }
    }

    private fun readHeaderLine(stream: java.io.InputStream): String {
        val sb = StringBuilder()
        var b: Int
        while (stream.read().also { b = it } != -1) {
            if (b.toChar() == '\n') break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun openOutputFile(meta: TransferMetadata): Pair<Uri, java.io.OutputStream>? {
        val resolver = contentResolver

        val (collection, relativePath) = when {
            meta.mimeType.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                        "${Environment.DIRECTORY_PICTURES}/XSensory"
            meta.mimeType.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                        "${Environment.DIRECTORY_MOVIES}/XSensory"
            meta.mimeType.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                        "${Environment.DIRECTORY_MUSIC}/XSensory"
            else ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                            "${Environment.DIRECTORY_DOWNLOADS}/XSensory"
                else
                    MediaStore.Files.getContentUri("external") to
                            "${Environment.DIRECTORY_DOWNLOADS}/XSensory"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, meta.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, meta.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)   // hidden until transfer done
            }
        }

        val uri = resolver.insert(collection, values) ?: return null
        val stream = resolver.openOutputStream(uri) ?: return null
        return uri to stream
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    /** Silent ongoing notification shown during the transfer (replaces the foreground one). */
    private fun buildOngoingNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ONGOING_ID)
            .setContentTitle("XSensory — Receiving file")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun updateOngoingNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ONGOING_ID, buildOngoingNotification(text))
    }

    /**
     * Fired once the file is fully saved.
     * - Plays the default notification sound
     * - Tapping it opens the file in the appropriate viewer app
     */
    private fun fireCompletionNotification(fileName: String, mimeType: String, fileUri: Uri) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent that opens the file in whatever app handles its MIME type
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETE_ID)
            .setContentTitle("File received")
            .setContentText(fileName)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setSound(soundUri)
            .setContentIntent(pendingOpen)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$fileName\nSaved to ${folderLabel(mimeType)}/XSensory — tap to open")
            )
            .build()

        nm.notify(NOTIF_COMPLETE_ID, notification)
    }

    private fun folderLabel(mimeType: String) = when {
        mimeType.startsWith("image/") -> "Pictures"
        mimeType.startsWith("video/") -> "Movies"
        mimeType.startsWith("audio/") -> "Music"
        else                          -> "Downloads"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Silent channel for the ongoing transfer progress
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ONGOING_ID,
                    "File Transfer Progress",
                    NotificationManager.IMPORTANCE_LOW        // no sound
                )
            )

            // Alerting channel for the completion notification (plays sound)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_COMPLETE_ID,
                    "File Received",
                    NotificationManager.IMPORTANCE_HIGH       // plays sound, shows heads-up
                ).apply {
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun closeServerSocket() {
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------

    companion object {
        private const val TAG                = "FileReceiverService"
        private const val NOTIF_ONGOING_ID   = 1002
        private const val NOTIF_COMPLETE_ID  = 1003
        private const val CHANNEL_ONGOING_ID = "xsensory_transfer_progress"
        private const val CHANNEL_COMPLETE_ID = "xsensory_transfer_complete"
        private const val BUFFER_SIZE        = 65_536
        private const val SO_TIMEOUT_MS      = 60_000

        var progressCallback: TransferProgressCallback? = null

        fun buildIntent(context: Context): Intent =
            Intent(context, FileReceiverService::class.java)

        private fun progressPercent(done: Long, total: Long) =
            if (total > 0) (done * 100 / total).toInt() else 0
    }
}
