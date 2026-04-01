package com.k2e7.xsensory.wifidirect

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Performs a two-device role handshake over the coordination port.
 *
 * Both devices connect to a rendezvous point (the group owner IP) on
 * COORD_PORT immediately after the Wi-Fi Direct connection forms.
 * Each sends one byte: 'S' (sender) or 'R' (receiver).
 * Each reads one byte back: the other device's choice.
 *
 * If roles conflict (both S or both R), [HandshakeResult.Conflict] is returned.
 * Otherwise [HandshakeResult.Resolved] carries the receiver's IP address.
 *
 * The group owner runs the tiny relay server ([runRelay]).
 * The client connects to it ([runClient]).
 */
object RoleHandshake {

    const val COORD_PORT = 8889
    private const val TAG = "RoleHandshake"
    private const val TIMEOUT_MS = 15_000

    sealed class HandshakeResult {
        /** Roles are consistent. [receiverIp] is where the sender should connect. */
        data class Resolved(val receiverIp: String) : HandshakeResult()
        /** Both devices chose the same role. */
        object Conflict : HandshakeResult()
        /** Something went wrong (timeout, IO error). */
        data class Error(val message: String) : HandshakeResult()
    }

    /**
     * Run by the GROUP OWNER after the user picks a role.
     * Opens a ServerSocket, accepts exactly one client, exchanges role bytes,
     * then returns the result.
     *
     * @param myRole  'S' or 'R'
     */
    fun runRelay(myRole: Char): HandshakeResult {
        Log.d(TAG, "runRelay: myRole=$myRole")
        return try {
            ServerSocket(COORD_PORT).use { server ->
                server.soTimeout = TIMEOUT_MS
                server.accept().use { client ->
                    val clientIp = client.inetAddress.hostAddress ?: ""
                    val out: OutputStream = client.getOutputStream()
                    val inp: InputStream  = client.getInputStream()

                    // Read client role first, then send ours
                    val clientRole = inp.read().toChar()
                    out.write(myRole.code)
                    out.flush()

                    Log.d(TAG, "runRelay: myRole=$myRole clientRole=$clientRole clientIp=$clientIp")
                    resolveRoles(myRole, clientRole, isOwner = true, ownerIp = "127.0.0.1", clientIp = clientIp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "runRelay error", e)
            HandshakeResult.Error(e.message ?: "Relay error")
        }
    }

    /**
     * Run by the CLIENT (non-owner) after the user picks a role.
     * Connects to the group owner's coordination port and exchanges role bytes.
     *
     * @param myRole       'S' or 'R'
     * @param ownerIp      group owner's IP address
     * @param myIp         this device's own IP (so owner knows where to reach us if we're receiver)
     */
    fun runClient(myRole: Char, ownerIp: String, myIp: String): HandshakeResult {
        Log.d(TAG, "runClient: myRole=$myRole ownerIp=$ownerIp myIp=$myIp")
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ownerIp, COORD_PORT), TIMEOUT_MS)
                val out: OutputStream = socket.getOutputStream()
                val inp: InputStream  = socket.getInputStream()

                // Send our role first, then read owner's role
                out.write(myRole.code)
                out.flush()
                val ownerRole = inp.read().toChar()

                Log.d(TAG, "runClient: myRole=$myRole ownerRole=$ownerRole")
                resolveRoles(myRole, ownerRole, isOwner = false, ownerIp = ownerIp, clientIp = myIp)
            }
        } catch (e: Exception) {
            Log.e(TAG, "runClient error", e)
            HandshakeResult.Error(e.message ?: "Client handshake error")
        }
    }

    /**
     * Given both roles and both IPs, determine the receiver's IP.
     * Called from both relay and client paths.
     *
     * [isOwner] — true if the calling device is the group owner.
     * The receiver's IP is what the sender needs to TCP-connect to on port 8888.
     * The group owner's IP is always reachable; the client IP is the source
     * address seen by the relay socket.
     */
    private fun resolveRoles(
        myRole: Char,
        theirRole: Char,
        isOwner: Boolean,
        ownerIp: String,
        clientIp: String
    ): HandshakeResult {
        if (myRole == theirRole) return HandshakeResult.Conflict

        // Receiver's IP is what the sender connects to on port 8888
        val receiverIp = when {
            myRole == 'R' -> if (isOwner) ownerIp else clientIp   // I am the receiver
            else          -> if (isOwner) clientIp else ownerIp   // they are the receiver
        }
        return HandshakeResult.Resolved(receiverIp)
    }
}
