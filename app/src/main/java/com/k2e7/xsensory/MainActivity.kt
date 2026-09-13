package com.k2e7.xsensory

import android.Manifest
import android.app.AlertDialog
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.k2e7.xsensory.databinding.ActivityMainBinding
import com.k2e7.xsensory.wifidirect.*
import android.content.Context
import android.content.Intent

class MainActivity : AppCompatActivity(), WifiDirectReceiver.WifiDirectListener {

    private lateinit var b: ActivityMainBinding
    private lateinit var wifiManager: WifiDirectManager
    private lateinit var receiver: WifiDirectReceiver
    private lateinit var intentFilter: IntentFilter

    private var pendingFileUri: Uri? = null
    private var groupOwnerAddress: String? = null
    private var isGroupOwner: Boolean = false
    private var handshakeDone: Boolean = false

    // -------------------------------------------------------------------------
    // Launchers
    // -------------------------------------------------------------------------

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) status("Permissions granted — ready")
            else status("Some permissions denied — features may not work")
        }

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                pendingFileUri = uri
                startSend()
            }
        }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        wifiManager = WifiDirectManager(this)
        receiver = WifiDirectReceiver(wifiManager.manager, wifiManager.channel, this)

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        setupRecyclerView()
        setupButtons()
        requestRequiredPermissions()
        resetButtonState()
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiManager.release()
    }

    // -------------------------------------------------------------------------
    // UI setup
    // -------------------------------------------------------------------------

    private fun setupRecyclerView() {
        b.rvPeers.layoutManager = LinearLayoutManager(this)
        b.rvPeers.adapter = PeerAdapter { device ->
            status("Connecting to ${device.deviceName}…")
            wifiManager.connect(
                device,
                onSuccess = { status("Connected — waiting for role dialog…") },
                onFailure = { status("Connection failed (reason $it)") }
            )
        }
    }

    private fun setupButtons() {
        b.btnDiscover.setOnClickListener {
            if (!hasPermissions()) { requestRequiredPermissions(); return@setOnClickListener }

            val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
            if (!wifiMgr.isWifiEnabled) {
                AlertDialog.Builder(this)
                    .setTitle("Wi-Fi is off")
                    .setMessage("Wi-Fi Direct requires Wi-Fi to be enabled. Turn it on now?")
                    .setCancelable(false)
                    .setPositiveButton("Turn on") { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                return@setOnClickListener
            }

            status("Discovering peers…")
            wifiManager.discoverPeers(
                onSuccess = { status("Scanning for nearby devices…") },
                onFailure = { status("Discovery failed (reason $it)") }
            )
        }

        b.btnSendFile.setOnClickListener {
            filePicker.launch("*/*")
        }

        b.btnReceive.setOnClickListener {
            startReceiving()
        }

        b.btnDisconnect.setOnClickListener {
            wifiManager.disconnect {
                runOnUiThread {
                    handshakeDone = false
                    groupOwnerAddress = null
                    resetButtonState()
                    status("Disconnected")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Role dialog + handshake
    // -------------------------------------------------------------------------

    /**
     * Show the role dialog. When the user picks a role:
     *  1. Run the RoleHandshake on a background thread
     *  2. If conflict → show conflict dialog
     *  3. If resolved → enable the correct button and store the receiver IP
     */
    private fun showRoleDialog(ownerIp: String, amIOwner: Boolean) {
        if (isFinishing || isDestroyed) return

        AlertDialog.Builder(this)
            .setTitle("Choose your role")
            .setMessage("What do you want to do?")
            .setCancelable(false)
            .setPositiveButton("Send a file") { _, _ ->
                status("Coordinating roles…")
                runHandshake(role = 'S', ownerIp = ownerIp, amIOwner = amIOwner)
            }
            .setNegativeButton("Receive a file") { _, _ ->
                status("Coordinating roles…")
                runHandshake(role = 'R', ownerIp = ownerIp, amIOwner = amIOwner)
            }
            .show()
    }

    private fun runHandshake(role: Char, ownerIp: String, amIOwner: Boolean) {
        Thread {
            val result = if (amIOwner) {
                // Group owner runs the relay server
                RoleHandshake.runRelay(role)
            } else {
                // Client connects to the relay
                val myIp = WifiDirectManager.getP2pIpAddress() ?: ""
                Log.d(TAG, "runHandshake: myIp=$myIp ownerIp=$ownerIp role=$role")
                RoleHandshake.runClient(role, ownerIp, myIp)
            }

            runOnUiThread { handleHandshakeResult(result, role) }
        }.start()
    }

    private fun handleHandshakeResult(result: RoleHandshake.HandshakeResult, myRole: Char) {
        when (result) {
            is RoleHandshake.HandshakeResult.Resolved -> {
                val receiverIp = result.receiverIp
                Log.d(TAG, "Handshake resolved: myRole=$myRole receiverIp=$receiverIp")

                // Store the receiver's IP so the sender knows where to connect
                groupOwnerAddress = receiverIp

                b.btnDiscover.visibility   = View.GONE
                b.btnDisconnect.visibility = View.VISIBLE

                if (myRole == 'S') {
                    b.btnSendFile.visibility = View.VISIBLE
                    b.btnReceive.visibility  = View.GONE
                    status("Role confirmed: SENDER — tap SEND FILE to choose a file")
                } else {
                    b.btnSendFile.visibility = View.GONE
                    b.btnReceive.visibility  = View.VISIBLE
                    status("Role confirmed: RECEIVER — tap RECEIVE to start waiting")
                }
            }

            is RoleHandshake.HandshakeResult.Conflict -> {
                // Both picked the same role — ask again
                AlertDialog.Builder(this)
                    .setTitle("Role conflict")
                    .setMessage("Both devices chose the same role. Please coordinate and try again.")
                    .setCancelable(false)
                    .setPositiveButton("Try again") { _, _ ->
                        val ownerIp = groupOwnerAddress ?: return@setPositiveButton
                        showRoleDialog(ownerIp, isGroupOwner)
                    }
                    .show()
            }

            is RoleHandshake.HandshakeResult.Error -> {
                status("Handshake failed: ${result.message} — tap Disconnect and reconnect")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Send / Receive
    // -------------------------------------------------------------------------

    private fun startSend() {
        val host = groupOwnerAddress ?: run {
            status("Not connected — cannot send")
            return
        }
        val uri = pendingFileUri ?: return

        showProgress(true)
        status("Sending file…")

        FileSenderService.progressCallback = object : TransferProgressCallback {
            override fun onProgress(bytesDone: Long, total: Long) {
                runOnUiThread {
                    if (total > 0) {
                        val pct = (bytesDone * 100 / total).toInt()
                        b.progressBar.progress = pct
                        b.tvProgressPercent.text = "$pct%"
                        b.tvProgressLabel.text = "Sending…"
                    }
                }
            }
            override fun onComplete(fileUri: Uri?) {
                runOnUiThread {
                    showProgress(false)
                    status("File sent successfully!")
                    pendingFileUri = null
                    FileSenderService.progressCallback = null
                }
            }
            override fun onError(message: String) {
                runOnUiThread {
                    showProgress(false)
                    status("Send error: $message")
                    FileSenderService.progressCallback = null
                }
            }
        }

        startService(FileSenderService.buildIntent(this, host, uri))
    }

    private fun startReceiving() {
        showProgress(true)
        status("Waiting for incoming file…")

        FileReceiverService.progressCallback = object : TransferProgressCallback {
            override fun onProgress(bytesDone: Long, total: Long) {
                runOnUiThread {
                    if (total > 0) {
                        val pct = (bytesDone * 100 / total).toInt()
                        b.progressBar.progress = pct
                        b.tvProgressPercent.text = "$pct%"
                        b.tvProgressLabel.text = "Receiving…"
                    }
                }
            }
            override fun onComplete(fileUri: Uri?) {
                runOnUiThread {
                    showProgress(false)
                    status("File received and saved!")
                    FileReceiverService.progressCallback = null
                }
            }
            override fun onError(message: String) {
                runOnUiThread {
                    showProgress(false)
                    status("Receive error: $message")
                    FileReceiverService.progressCallback = null
                }
            }
        }

        startService(FileReceiverService.buildIntent(this))
    }

    // -------------------------------------------------------------------------
    // WifiDirectListener
    // -------------------------------------------------------------------------

    override fun onWifiP2pStateChanged(enabled: Boolean) {
        status(if (enabled) "Wi-Fi Direct is ON" else "Wi-Fi Direct is OFF — enable it first")
    }

    override fun onPeersChanged(peers: List<WifiP2pDevice>) {
        (b.rvPeers.adapter as PeerAdapter).submitList(peers)
        status("Found ${peers.size} peer(s)")
    }

    override fun onConnectionChanged(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            runOnUiThread {
                handshakeDone = false
                groupOwnerAddress = null
                resetButtonState()
                status("Disconnected")
            }
            return
        }

        // Guard against the broadcast firing multiple times
        if (handshakeDone) return
        handshakeDone = true

        val ownerIp = info.groupOwnerAddress?.hostAddress ?: return
        isGroupOwner = info.isGroupOwner
        groupOwnerAddress = ownerIp

        Log.d(TAG, "onConnectionChanged: isGroupOwner=$isGroupOwner ownerIp=$ownerIp")

        runOnUiThread {
            b.btnDiscover.visibility   = View.GONE
            b.btnDisconnect.visibility = View.VISIBLE
            showRoleDialog(ownerIp, isGroupOwner)
        }
    }

    override fun onDeviceChanged(device: WifiP2pDevice) {}

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private fun hasPermissions() = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRequiredPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun resetButtonState() {
        b.btnDiscover.visibility   = View.VISIBLE
        b.btnSendFile.visibility   = View.GONE
        b.btnReceive.visibility    = View.GONE
        b.btnDisconnect.visibility = View.GONE
    }

    private fun status(msg: String) = runOnUiThread { b.tvStatus.text = msg }

    private fun showProgress(show: Boolean) = runOnUiThread {
        b.layoutProgress.visibility = if (show) View.VISIBLE else View.GONE
        b.progressBar.progress = 0
        b.tvProgressPercent.text = "0%"
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}