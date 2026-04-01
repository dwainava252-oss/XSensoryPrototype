package com.k2e7.xsensory.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket

class BluetoothClient {

    @SuppressLint("MissingPermission")

    
    fun sendMessage(device: BluetoothDevice, message: String) {

        Thread {

            var socket: BluetoothSocket? = null

            try {

                val adapter = BluetoothAdapter.getDefaultAdapter()
                adapter.cancelDiscovery()

                try {

                    println("CLIENT: trying normal connection")

                    socket = device.createRfcommSocketToServiceRecord(
                        BluetoothConstants.APP_UUID
                    )

                    socket.connect()

                } catch (e: Exception) {

                    println("CLIENT: fallback connection")

                    val method = device.javaClass.getMethod(
                        "createRfcommSocket",
                        Int::class.javaPrimitiveType
                    )

                    socket = method.invoke(device, 1) as BluetoothSocket
                    socket.connect()
                }

                println("CLIENT: connected")

                val output = socket!!.outputStream

                output.write(message.toByteArray())
                output.flush()

                Thread.sleep(500)

                socket.close()

                println("CLIENT: closed")

            } catch (e: Exception) {
                e.printStackTrace()
            }

        }.start()
    }
}