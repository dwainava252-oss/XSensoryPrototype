package com.k2e7.xsensory.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager

/**
 * Receives all Wi-Fi Direct system broadcasts and routes them to
 * [WifiDirectManager] via its listener interface.
 *
 * Register/unregister this in onResume/onPause of the Activity.
 */
class WifiDirectReceiver(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val listener: WifiDirectListener
) : BroadcastReceiver() {

    /** Implement this in your Activity / ViewModel. */
    interface WifiDirectListener {
        /** Wi-Fi Direct hardware was enabled or disabled. */
        fun onWifiP2pStateChanged(enabled: Boolean)

        /** The list of discovered peers changed. */
        fun onPeersChanged(peers: List<WifiP2pDevice>)

        /**
         * A connection was established (or lost).
         * [info] is null when the connection was dropped.
         */
        fun onConnectionChanged(info: WifiP2pInfo?)

        /** Our own device details changed (name, status, etc.). */
        fun onDeviceChanged(device: WifiP2pDevice)
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {

            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                listener.onWifiP2pStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                manager.requestPeers(channel) { peerList ->
                    listener.onPeersChanged(peerList.deviceList.toList())
                }
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(
                    WifiP2pManager.EXTRA_NETWORK_INFO
                )
                if (networkInfo?.isConnected == true) {
                    // Fetch the connection details (group owner IP, etc.)
                    manager.requestConnectionInfo(channel) { info ->
                        listener.onConnectionChanged(info)
                    }
                } else {
                    listener.onConnectionChanged(null)
                }
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = intent.getParcelableExtra<WifiP2pDevice>(
                    WifiP2pManager.EXTRA_WIFI_P2P_DEVICE
                ) ?: return
                listener.onDeviceChanged(device)
            }
        }
    }
}
