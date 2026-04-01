package com.k2e7.xsensory.dialogs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.appcompat.app.AlertDialog

class DevicePickerDialog(
    private val context: Context,
    private val onDeviceSelected: (BluetoothDevice) -> Unit
) {
    @SuppressLint("MissingPermission")
    fun show() {

        val adapter = BluetoothAdapter.getDefaultAdapter()

        val devices = adapter.bondedDevices.toList()

        if (devices.isEmpty()) {

            AlertDialog.Builder(context)
                .setTitle("No Paired Devices")
                .setMessage("Please pair another phone in Bluetooth settings first.")
                .setPositiveButton("Open Settings") { _, _ ->

                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    context.startActivity(intent)

                }
                .setNegativeButton("Cancel", null)
                .show()

            return
        }

        val names = devices.map { it.name }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Select Device")
            .setItems(names) { _, which ->
                onDeviceSelected(devices[which])
            }
            .show()
    }
}