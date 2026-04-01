package com.k2e7.xsensory

import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.k2e7.xsensory.databinding.ItemPeerBinding

/**
 * Simple ListAdapter that displays discovered Wi-Fi Direct peers and fires
 * [onDeviceClick] when the user taps one.
 *
 * Requires a list item layout  res/layout/item_peer.xml  that has:
 *   - tvDeviceName  (TextView)
 *   - tvDeviceStatus (TextView)
 */
class PeerAdapter(
    private val onDeviceClick: (WifiP2pDevice) -> Unit
) : ListAdapter<WifiP2pDevice, PeerAdapter.ViewHolder>(DIFF_CALLBACK) {

    inner class ViewHolder(private val b: ItemPeerBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(device: WifiP2pDevice) {
            b.tvDeviceName.text   = device.deviceName.ifBlank { device.deviceAddress }
            b.tvDeviceStatus.text = device.statusString()
            b.root.setOnClickListener { onDeviceClick(device) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<WifiP2pDevice>() {
            override fun areItemsTheSame(a: WifiP2pDevice, b: WifiP2pDevice) =
                a.deviceAddress == b.deviceAddress
            override fun areContentsTheSame(a: WifiP2pDevice, b: WifiP2pDevice) =
                a.deviceName == b.deviceName && a.status == b.status
        }
    }
}

private fun WifiP2pDevice.statusString() = when (status) {
    WifiP2pDevice.CONNECTED     -> "Connected"
    WifiP2pDevice.INVITED       -> "Invited"
    WifiP2pDevice.FAILED        -> "Failed"
    WifiP2pDevice.AVAILABLE     -> "Available"
    WifiP2pDevice.UNAVAILABLE   -> "Unavailable"
    else                        -> "Unknown"
}
