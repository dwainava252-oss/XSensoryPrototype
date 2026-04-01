package com.k2e7.xsensory.bluetooth

import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream

class BluetoothConnection(
    private val socket: BluetoothSocket,
    private val onMessage: (String) -> Unit
) {

    private lateinit var input: InputStream
    private lateinit var output: OutputStream

    fun start() {

        input = socket.inputStream
        output = socket.outputStream

        Thread {

            val buffer = ByteArray(1024)

            while (true) {

                try {

                    val bytes = input.read(buffer)

                    if (bytes <= 0) {
                        println("CONNECTION: stream closed")
                        break
                    }

                    val msg = String(buffer, 0, bytes)

                    println("CONNECTION: received = $msg")

                    onMessage(msg)

                } catch (e: Exception) {
                    println("CONNECTION: socket closed")
                    break
                }
            }

        }.start()
    }

    fun send(message: String) {

        Thread {

            try {
                output.write(message.toByteArray())
                output.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }

        }.start()
    }
}