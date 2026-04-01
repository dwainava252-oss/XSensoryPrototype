package com.k2e7.xsensory.wifidirect

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import java.net.NetworkInterface

class WifiDirectManager(context: Context) {

    val manager: WifiP2pManager =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager

    val channel: WifiP2pManager.Channel =
        manager.initialize(context, context.mainLooper, null)

    @SuppressLint("MissingPermission")
    fun discoverPeers(onSuccess: () -> Unit = {}, onFailure: (Int) -> Unit = {}) {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "discoverPeers: started"); onSuccess() }
            override fun onFailure(reason: Int) { Log.w(TAG, "discoverPeers: failed reason=$reason"); onFailure(reason) }
        })
    }

    fun stopDiscovery(onSuccess: () -> Unit = {}) {
        manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onSuccess()
            override fun onFailure(reason: Int) { Log.w(TAG, "stopDiscovery: failed reason=$reason") }
        })
    }

    @SuppressLint("MissingPermission")
    fun connect(device: WifiP2pDevice, onSuccess: () -> Unit = {}, onFailure: (Int) -> Unit = {}) {
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
        }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "connect: sent to ${device.deviceAddress}"); onSuccess() }
            override fun onFailure(reason: Int) { Log.w(TAG, "connect: failed reason=$reason"); onFailure(reason) }
        })
    }

    fun disconnect(onSuccess: () -> Unit = {}) {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "disconnect: group removed"); onSuccess() }
            override fun onFailure(reason: Int) { Log.w(TAG, "disconnect: failed reason=$reason") }
        })
    }

    fun release() { runCatching { channel.close() } }

    companion object {
        private const val TAG = "WifiDirectManager"

        /**
         * Returns this device's IP on the Wi-Fi Direct (p2p) interface.
         * The p2p interface is named "p2p0" or "p2p-wlan0-*" depending on the device.
         * Falls back to null if not found.
         */
        fun getP2pIpAddress(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces()
                    ?.asSequence()
                    ?.filter { it.name.startsWith("p2p") }
                    ?.flatMap { it.inetAddresses.asSequence() }
                    ?.filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    ?.map { it.hostAddress }
                    ?.firstOrNull()
            } catch (e: Exception) {
                Log.e(TAG, "getP2pIpAddress error", e)
                null
            }
        }
    }
}