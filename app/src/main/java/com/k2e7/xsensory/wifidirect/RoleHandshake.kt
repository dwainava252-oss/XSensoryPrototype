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
 *
 * FIX: runClient now retries for up to RETRY_TOTAL_MS before giving up.
 * This handles the race condition where the Wi-Fi Direct TCP link is not
 * yet fully stable when the role dialog appears — especially at longer
 * distances (10+ metres) where the P2P link takes slightly longer to settle.
 */
object RoleHandshake {

    const val COORD_PORT = 8889
    private const val TAG = "RoleHandshake"

    // How long the owner's ServerSocket waits for the client to arrive.
    // Increased to give the client's retry loop enough room.
    private const val SERVER_ACCEPT_TIMEOUT_MS = 60_000

    // Each individual connect() attempt by the client times out after this.
    private const val CONNECT_ATTEMPT_TIMEOUT_MS = 3_000

    // The client keeps retrying for this long before giving up entirely.
    private const val RETRY_TOTAL_MS = 55_000L

    // Pause between retry attempts.
    private const val RETRY_INTERVAL_MS = 1_500L

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
                // Give the client's retry loop plenty of time to connect
                server.soTimeout = SERVER_ACCEPT_TIMEOUT_MS
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
     * Retries the connection for up to [RETRY_TOTAL_MS] milliseconds so that
     * temporary TCP unavailability right after the P2P link forms (common at
     * distances of 10+ metres) does not cause an immediate failure.
     *
     * @param myRole       'S' or 'R'
     * @param ownerIp      group owner's IP address
     * @param myIp         this device's own IP (so owner knows where to reach us if we're receiver)
     */
    fun runClient(myRole: Char, ownerIp: String, myIp: String): HandshakeResult {
        Log.d(TAG, "runClient: myRole=$myRole ownerIp=$ownerIp myIp=$myIp")

        val deadline = System.currentTimeMillis() + RETRY_TOTAL_MS
        var attempt = 0
        var lastError: Exception? = null

        while (System.currentTimeMillis() < deadline) {
            attempt++
            Log.d(TAG, "runClient: attempt $attempt connecting to $ownerIp:$COORD_PORT")

            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(ownerIp, COORD_PORT), CONNECT_ATTEMPT_TIMEOUT_MS)
                    val out: OutputStream = socket.getOutputStream()
                    val inp: InputStream  = socket.getInputStream()

                    // Send our role first, then read owner's role
                    out.write(myRole.code)
                    out.flush()
                    val ownerRole = inp.read().toChar()

                    Log.d(TAG, "runClient: myRole=$myRole ownerRole=$ownerRole (attempt $attempt)")
                    return resolveRoles(myRole, ownerRole, isOwner = false, ownerIp = ownerIp, clientIp = myIp)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "runClient: attempt $attempt failed — ${e.message}")

                // Sleep before retrying, but don't overshoot the deadline
                val remaining = deadline - System.currentTimeMillis()
                if (remaining > 0) {
                    Thread.sleep(minOf(RETRY_INTERVAL_MS, remaining))
                }
            }
        }

        Log.e(TAG, "runClient: all $attempt attempt(s) failed", lastError)
        return HandshakeResult.Error(
            "Handshake failed after $attempt attempt(s) — make sure both phones are connected and try again. (${lastError?.message})"
        )
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
