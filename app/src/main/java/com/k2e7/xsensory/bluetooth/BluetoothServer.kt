package com.k2e7.xsensory.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket

class BluetoothServer {

    @SuppressLint("MissingPermission")
    fun startServer(onMessage: (String) -> Unit) {

        Thread {

            try {

                val adapter = BluetoothAdapter.getDefaultAdapter()

                println("SERVER: starting")

                val serverSocket: BluetoothServerSocket =
                    adapter.listenUsingRfcommWithServiceRecord(
                        "BT_CHAT",
                        BluetoothConstants.APP_UUID
                    )

                println("SERVER: waiting for connection")

                val socket: BluetoothSocket = serverSocket.accept()

                println("SERVER: client connected")

                val input = socket.inputStream

                val buffer = ByteArray(1024)

                val bytes = input.read(buffer)

                if (bytes > 0) {

                    val message = String(buffer, 0, bytes)

                    println("SERVER: received = $message")

                    onMessage(message)
                }

                socket.close()
                serverSocket.close()

                println("SERVER: closed")

            } catch (e: Exception) {
                e.printStackTrace()
            }

        }.start()
    }
}