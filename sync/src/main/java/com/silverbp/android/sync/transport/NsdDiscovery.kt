package com.silverbp.android.sync.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Concrete NSD-based discovery + listener for the SilverBP sync service.
 * Mirrors iOS `BonjourDiscovery.swift`. NsdManager is callback-based; we
 * funnel its events into coroutine-friendly channels.
 *
 * Multicast permission required: `android.permission.CHANGE_WIFI_MULTICAST_STATE`
 * and `android.permission.INTERNET`. Both are added to `:app`'s manifest.
 *
 * Lifecycle:
 *   1. [startAdvertising] — opens a TCP `ServerSocket`, registers an NSD service
 *      pointing at it. Inbound connections surface via [acceptIncoming].
 *   2. [startBrowsing] — discovers peers and exposes them via [waitForPeer].
 *   3. [stop] — unregisters the service, stops discovery, closes the listener.
 */
class NsdDiscovery(
    private val context: Context,
) {
    data class Peer(
        val bonjourName: String,
        val deviceId: String,
        val pubKeyFingerprint: String,
        val host: InetAddress,
        val port: Int,
    )

    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var serverSocket: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var myAdvertisedName: String? = null
    private val seenPeerNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val peerChannel = Channel<Peer>(Channel.BUFFERED)
    private val incomingChannel = Channel<Socket>(Channel.BUFFERED)

    /**
     * Open a TCP listener on an ephemeral port and register an NSD service
     * advertising it. Subsequent [acceptIncoming] calls await a peer connecting.
     */
    suspend fun startAdvertising(deviceId: String, pubKeyFingerprint: String) {
        val socket = ServerSocket(0).apply {
            reuseAddress = true
        }
        serverSocket = socket
        val port = socket.localPort

        val info = NsdServiceInfo().apply {
            serviceName = deviceId
            serviceType = SyncBonjour.SERVICE_TYPE
            this.port = port
            setAttribute(SyncBonjour.TXT_KEY_DEVICE_ID, deviceId)
            setAttribute(SyncBonjour.TXT_KEY_PUBKEY_FINGERPRINT, pubKeyFingerprint)
            setAttribute(SyncBonjour.TXT_KEY_PROTOCOL_VERSION, SyncBonjour.CURRENT_PROTOCOL_VERSION)
        }

        suspendCancellableCoroutine { c ->
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    // OS may rename to disambiguate (e.g. add "(2)") when the
                    // same name is already advertised; capture the actual name
                    // so the browser can filter our own service.
                    myAdvertisedName = serviceInfo.serviceName
                    c.resume(Unit)
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    c.resumeWithException(IOException("NSD registration failed: $errorCode"))
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registrationListener = listener
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            c.invokeOnCancellation {
                runCatching { nsdManager.unregisterService(listener) }
                runCatching { serverSocket?.close() }
            }
        }
    }

    /** Suspend until the next inbound TCP connection arrives. */
    suspend fun acceptIncoming(): Socket {
        // ServerSocket.accept() is blocking — caller should drive on Dispatchers.IO.
        val s = serverSocket ?: error("startAdvertising not called")
        return s.accept()
    }

    /**
     * Begin discovering peers. Resolved peers are pushed onto the internal
     * channel; consume via [waitForPeer].
     */
    fun startBrowsing() {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != SyncBonjour.SERVICE_TYPE.trimEnd('.')) return
                // Skip our own advertisement when both Show-QR and Scan-QR
                // run on the same device (would otherwise pair with self).
                if (serviceInfo.serviceName == myAdvertisedName) return
                if (!seenPeerNames.add(serviceInfo.serviceName)) return
                nsdManager.resolveService(serviceInfo, makeResolveListener())
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(SyncBonjour.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun makeResolveListener(): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val attrs = serviceInfo.attributes
                val deviceId = attrs[SyncBonjour.TXT_KEY_DEVICE_ID]
                    ?.toString(StandardCharsets.UTF_8) ?: return
                val fp = attrs[SyncBonjour.TXT_KEY_PUBKEY_FINGERPRINT]
                    ?.toString(StandardCharsets.UTF_8) ?: return
                val ver = attrs[SyncBonjour.TXT_KEY_PROTOCOL_VERSION]
                    ?.toString(StandardCharsets.UTF_8) ?: return
                if (ver != SyncBonjour.CURRENT_PROTOCOL_VERSION) return
                val host = serviceInfo.host ?: return
                val port = serviceInfo.port
                if (port <= 0) return
                peerChannel.trySend(
                    Peer(
                        bonjourName = serviceInfo.serviceName,
                        deviceId = deviceId,
                        pubKeyFingerprint = fp,
                        host = host,
                        port = port,
                    ),
                )
            }
        }

    /** Suspend until the next discovered peer. */
    suspend fun waitForPeer(): Peer = peerChannel.receive()

    /**
     * Connect to a discovered peer. Caller wraps the resulting Socket via
     * [StreamFrameChannel].
     */
    fun connect(peer: Peer): Socket {
        val s = Socket()
        s.connect(java.net.InetSocketAddress(peer.host, peer.port), 5_000)
        return s
    }

    fun stop() {
        registrationListener?.let { runCatching { nsdManager.unregisterService(it) } }
        registrationListener = null
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        peerChannel.close()
        incomingChannel.close()
    }
}
